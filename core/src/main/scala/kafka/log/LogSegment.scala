/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.log

import kafka.message._
import kafka.common._
import kafka.utils._
import kafka.server.{LogOffsetMetadata, FetchDataInfo}
import org.apache.kafka.common.errors.CorruptRecordException

import scala.math._
import java.io.{IOException, File}


 /**
 * A segment of the log. Each segment has two components: a log and an index. The log is a FileMessageSet containing
 * the actual messages. The index is an OffsetIndex that maps from logical offsets to physical file positions. Each
 * segment has a base offset which is an offset <= the least offset of any message in this segment and > any offset in
 * any previous segment.
 *
 * A segment with a base offset of [base_offset] would be stored in two files, a [base_offset].index and a [base_offset].log file.
 *
 * @param log The message set containing log entries
 * @param index The offset index
 * @param baseOffset A lower bound on the offsets in this segment
 * @param indexIntervalBytes The approximate number of bytes between entries in the index
 * @param time The time instance
 */
 // 每个日志文件对应一个LogSegment
 // LogSegment封装了一个FileMessageSet和OffsetIndex对象
 // 提供日志文件和索引文件的读写功能
 // log：用于操作对应日志文件的FileMessageSet对象
 // index：用于操作索引文件的OffsetIndex对象
 // baseOffset：LogSegment中第一条消息的offset值
 // indexIntervalBytes：索引项之间间隔的最小字节数
 // bytesSinceLastIndexEntry：记录从上次添加索引之后，在日志文件中累计加入的Message集合的字节数，用于判断下次添加索引的时间
 // created：标识LogSegment对象创建时间
@nonthreadsafe
class LogSegment(val log: FileMessageSet,
                 val index: OffsetIndex,
                 val baseOffset: Long,
                 val indexIntervalBytes: Int,
                 val rollJitterMs: Long,
                 time: Time) extends Logging {

  var created = time.milliseconds

  /* the number of bytes since we last added an entry in the offset index */
  private var bytesSinceLastIndexEntry = 0

  def this(dir: File, startOffset: Long, indexIntervalBytes: Int, maxIndexSize: Int, rollJitterMs: Long, time: Time, fileAlreadyExists: Boolean = false, initFileSize: Int = 0, preallocate: Boolean = false) =
    this(new FileMessageSet(file = Log.logFilename(dir, startOffset), fileAlreadyExists = fileAlreadyExists, initFileSize = initFileSize, preallocate = preallocate),
         new OffsetIndex(Log.indexFilename(dir, startOffset), baseOffset = startOffset, maxIndexSize = maxIndexSize),
         startOffset,
         indexIntervalBytes,
         rollJitterMs,
         time)

  /* Return the size in bytes of this log segment */
  def size: Long = log.sizeInBytes()

  /**
   * Append the given messages starting with the given offset. Add
   * an entry to the index if needed.
   *
   * It is assumed this method is being called from within a lock.
   *
   * @param offset The first offset in the message set.
   * @param messages The messages to append.
   */
  // offset是message中的第一条消息的offset
  // 如果是压缩文件，，则是第一条内层消息的offset
  @nonthreadsafe
  def append(offset: Long, messages: ByteBufferMessageSet) {
    if (messages.sizeInBytes > 0) {
      trace("Inserting %d bytes at offset %d at position %d".format(messages.sizeInBytes, offset, log.sizeInBytes()))
      // append an entry to the index (if needed)

      // 检查是满足添加索引条件
      if(bytesSinceLastIndexEntry > indexIntervalBytes) {
        // 添加索引
        index.append(offset, log.sizeInBytes())
        // 成功添加索引后，bytesSinceLastIndexEntry重置为0
        this.bytesSinceLastIndexEntry = 0
      }
      // append the messages

      // 写日志文件
      log.append(messages)
      this.bytesSinceLastIndexEntry += messages.sizeInBytes
    }
  }

  /**
   * Find the physical file position for the first message with offset >= the requested offset.
   *
   * The lowerBound argument is an optimization that can be used if we already know a valid starting position
   * in the file higher than the greatest-lower-bound from the index.
   *
   * @param offset The offset we want to translate
   * @param startingFilePosition A lower bound on the file position from which to begin the search. This is purely an optimization and
   * when omitted, the search will begin at the position in the offset index.
   *
   * @return The position in the log storing the message with the least offset >= the requested offset or null if no message meets this criteria.
   */
  @threadsafe
  private[log] def translateOffset(offset: Long, startingFilePosition: Int = 0): OffsetPosition = {
    // 通过offset，完成第一步对索引文件的查找
    val mapping = index.lookup(offset)
    // 通过FileMessageSet完成对日志文件的查找
    log.searchFor(offset, max(mapping.position, startingFilePosition))
  }

  /**
   * Read a message set from this segment beginning with the first offset >= startOffset. The message set will include
   * no more than maxSize bytes and will end before maxOffset if a maxOffset is specified.
   *
   * @param startOffset A lower bound on the first offset to include in the message set we read
   * @param maxSize The maximum number of bytes to include in the message set we read
   * @param maxOffset An optional maximum offset for the message set we read
   * @param maxPosition The maximum position in the log segment that should be exposed for read
   *
   * @return The fetched data and the offset metadata of the first message whose offset is >= startOffset,
   *         or null if the startOffset is larger than the largest offset in this log
   */
  // startOffset：读取的起始消息的offset
  // maxOffset：读取结束的offset，可以为空
  // maxSize：指定读取的最大字节数
  // maxPosition：指定读取的最大物理地址
  @threadsafe
  def read(startOffset: Long, maxOffset: Option[Long], maxSize: Int, maxPosition: Long = size): FetchDataInfo = {
    if(maxSize < 0)
      throw new IllegalArgumentException("Invalid max size for log read (%d)".format(maxSize))

    // 日志文件长度
    val logSize = log.sizeInBytes // this may change, need to save a consistent copy
    // 将startOffset转换成startPosition（offset转换成物理地址）
    val startPosition = translateOffset(startOffset)

    // if the start position is already off the end of the log, return null
    if(startPosition == null)
      return null

    val offsetMetadata = new LogOffsetMetadata(startOffset, this.baseOffset, startPosition.position)

    // if the size is zero, still return a log segment but with zero size
    if(maxSize == 0)
      return FetchDataInfo(offsetMetadata, MessageSet.Empty)

    // calculate the length of the message set to read based on whether or not they gave us a maxOffset
    // 计算读取的字节数
    val length = maxOffset match {
      case None =>
        // no max offset, just read until the max position
        // maxOffset为空，则由maxPosition，maxSize共同决定读取长度
        min((maxPosition - startPosition.position).toInt, maxSize)
      case Some(offset) =>
        // there is a max offset, translate it to a file position and use that to calculate the max read size;
        // when the leader of a partition changes, it's possible for the new leader's high watermark to be less than the
        // true high watermark in the previous leader for a short window. In this window, if a consumer fetches on an
        // offset between new leader's high watermark and the log end offset, we want to return an empty response.
        if(offset < startOffset)
          return FetchDataInfo(offsetMetadata, MessageSet.Empty)
        // 将maxOffset转换成物理地址
        val mapping = translateOffset(offset, startPosition.position)
        val endPosition =
          if(mapping == null)
          // maxOffset超出了
            logSize // the max offset is off the end of the log, use the end of the file
          else
            mapping.position
        // 由maxOffset，maxPosition，maxSize共同决定
        min(min(maxPosition, endPosition) - startPosition.position, maxSize).toInt
    }

    // maxOffset通常是Replica的HW，即消费者最多只能读取到HW这个位置的消息
    // 通过FileMessageSet.read()方法的介绍
    // FetchDataInfo的第二个参数是按照读取起始位置的长度生成一个分片的FileMessageSet对象
    // 并没有真正的读取到内存中
    FetchDataInfo(offsetMetadata, log.read(startPosition.position, length))
  }

  /**
   * Run recovery on the given segment. This will rebuild the index from the log file and lop off any invalid bytes from the end of the log and index.
   *
   * @param maxMessageSize A bound the memory allocation in the case of a corrupt message size--we will assume any message larger than this
   * is corrupt.
   *
   * @return The number of bytes truncated from the log
   */
  // 根据日志文件重建索引
  // 同时验证日志的合法性
  // 重建索引过程中，遇到压缩消息需要进行解压
  @nonthreadsafe
  def recover(maxMessageSize: Int): Int = {
    // 清空索引文件，底层只是移动position指针，后续写入会覆盖原有内容
    index.truncate()
    // 修改索引文件大小
    index.resize(index.maxIndexSize)
    // 记录已经通过验证的字节数
    var validBytes = 0
    // 最后一个索引项对应的物理地址
    var lastIndexEntry = 0
    // FileMessageSet的迭代器
    val iter = log.iterator(maxMessageSize)
    try {
      while(iter.hasNext) {
        val entry = iter.next
        // 验证message的合法性，失败抛异常
        entry.message.ensureValid()
        // 符合添加索引的条件
        if(validBytes - lastIndexEntry > indexIntervalBytes) {
          // we need to decompress the message, if required, to get the offset of the first uncompressed message
          val startOffset =
            entry.message.compressionCodec match {
              case NoCompressionCodec =>
                entry.offset
              case _ =>
                // 压缩消息，获取第一个消息的offset
                ByteBufferMessageSet.deepIterator(entry).next().offset
          }
          // 添加索引
          index.append(startOffset, validBytes)
          // 修改lastIndexEntry
          lastIndexEntry = validBytes
        }
        // 累加validBytes
        validBytes += MessageSet.entrySize(entry.message)
      }
    } catch {
      case e: CorruptRecordException =>
        logger.warn("Found invalid messages in log segment %s at byte offset %d: %s.".format(log.file.getAbsolutePath, validBytes, e.getMessage))
    }
    val truncated = log.sizeInBytes - validBytes
    // 对日志文件进行截断，抛弃后面验证失败的message
    log.truncateTo(validBytes)
    // 对索引文件进行相应的截断
    index.trimToValidSize()
    // 返回截掉的字节数
    truncated
  }

  override def toString() = "LogSegment(baseOffset=" + baseOffset + ", size=" + size + ")"

  /**
   * Truncate off all index and log entries with offsets >= the given offset.
   * If the given offset is larger than the largest message in this segment, do nothing.
   * @param offset The offset to truncate to
   * @return The number of log bytes truncated
   */
  @nonthreadsafe
  def truncateTo(offset: Long): Int = {
    val mapping = translateOffset(offset)
    if(mapping == null)
      return 0
    index.truncateTo(offset)
    // after truncation, reset and allocate more space for the (new currently  active) index
    index.resize(index.maxIndexSize)
    val bytesTruncated = log.truncateTo(mapping.position)
    if(log.sizeInBytes == 0)
      created = time.milliseconds
    bytesSinceLastIndexEntry = 0
    bytesTruncated
  }

  /**
   * Calculate the offset that would be used for the next message to be append to this segment.
   * Note that this is expensive.
   */
  @threadsafe
  def nextOffset(): Long = {
    val ms = read(index.lastOffset, None, log.sizeInBytes)
    if(ms == null) {
      baseOffset
    } else {
      ms.messageSet.lastOption match {
        case None => baseOffset
        case Some(last) => last.nextOffset
      }
    }
  }

  /**
   * Flush this log segment to disk
   */
  @threadsafe
  def flush() {
    LogFlushStats.logFlushTimer.time {
      log.flush()
      index.flush()
    }
  }

  /**
   * Change the suffix for the index and log file for this log segment
   */
  def changeFileSuffixes(oldSuffix: String, newSuffix: String) {

    def kafkaStorageException(fileType: String, e: IOException) =
      new KafkaStorageException(s"Failed to change the $fileType file suffix from $oldSuffix to $newSuffix for log segment $baseOffset", e)

    try log.renameTo(new File(CoreUtils.replaceSuffix(log.file.getPath, oldSuffix, newSuffix)))
    catch {
      case e: IOException => throw kafkaStorageException("log", e)
    }
    try index.renameTo(new File(CoreUtils.replaceSuffix(index.file.getPath, oldSuffix, newSuffix)))
    catch {
      case e: IOException => throw kafkaStorageException("index", e)
    }
  }

  /**
   * Close this log segment
   */
  def close() {
    CoreUtils.swallow(index.close)
    CoreUtils.swallow(log.close)
  }

  /**
   * Delete this log segment from the filesystem.
   * @throws KafkaStorageException if the delete fails.
   */
  def delete() {
    val deletedLog = log.delete()
    val deletedIndex = index.delete()
    if(!deletedLog && log.file.exists)
      throw new KafkaStorageException("Delete of log " + log.file.getName + " failed.")
    if(!deletedIndex && index.file.exists)
      throw new KafkaStorageException("Delete of index " + index.file.getName + " failed.")
  }

  /**
   * The last modified time of this log segment as a unix time stamp
   */
  def lastModified = log.file.lastModified

  /**
   * Change the last modified time for this log segment
   */
  def lastModified_=(ms: Long) = {
    log.file.setLastModified(ms)
    index.file.setLastModified(ms)
  }
}
