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

import kafka.utils._
import kafka.message._
import kafka.common._
import kafka.metrics.KafkaMetricsGroup
import kafka.server.{BrokerTopicStats, FetchDataInfo, LogOffsetMetadata}
import java.io.{File, IOException}
import java.util.concurrent.{ConcurrentNavigableMap, ConcurrentSkipListMap}
import java.util.concurrent.atomic._
import java.text.NumberFormat

import org.apache.kafka.common.errors.{CorruptRecordException, OffsetOutOfRangeException, RecordBatchTooLargeException, RecordTooLargeException}
import org.apache.kafka.common.record.TimestampType

import scala.collection.JavaConversions
import com.yammer.metrics.core.Gauge
import org.apache.kafka.common.utils.Utils

object LogAppendInfo {
  val UnknownLogAppendInfo = LogAppendInfo(-1, -1, Message.NoTimestamp, NoCompressionCodec, NoCompressionCodec, -1, -1, false)
}

/**
 * Struct to hold various quantities we compute about each message set before appending to the log
 * @param firstOffset The first offset in the message set
 * @param lastOffset The last offset in the message set
 * @param timestamp The log append time (if used) of the message set, otherwise Message.NoTimestamp
 * @param sourceCodec The source codec used in the message set (send by the producer)
 * @param targetCodec The target codec of the message set(after applying the broker compression configuration if any)
 * @param shallowCount The number of shallow messages
 * @param validBytes The number of valid bytes
 * @param offsetsMonotonic Are the offsets in this message set monotonically increasing
 */
case class LogAppendInfo(var firstOffset: Long,
                         var lastOffset: Long,
                         var timestamp: Long,
                         sourceCodec: CompressionCodec,
                         targetCodec: CompressionCodec,
                         shallowCount: Int,
                         validBytes: Int,
                         offsetsMonotonic: Boolean)


/**
 * An append-only log for storing messages.
 *
 * The log is a sequence of LogSegments, each with a base offset denoting the first message in the segment.
 *
 * New log segments are created according to a configurable policy that controls the size in bytes or time interval
 * for a given segment.
 *
 * @param dir The directory in which log segments are created.
 * @param config The log configuration settings
 * @param recoveryPoint The offset at which to begin recovery--i.e. the first offset which has not been flushed to disk
 * @param scheduler The thread pool scheduler used for background actions
 * @param time The time instance used for checking the clock
 *
 */
// 多个LogSegment对象的顺序组合，形成一个逻辑日志
// 为了快速定位LogSegment，Log使用了SkipList对LogSegment进行管理
// dir：Log对应的磁盘目录
// lock：log的修改需要同步，因为可能多个handler线程并发向同一个log追加消息
// config：Log相关的配置信息
// recoveryPoint：指定恢复操作的起始offset，之前的message已经刷新到磁盘上持久存储
//              出现宕机，只需恢复recoveryPoint之后的消息即可
// nextOffsetMetadata：LogOffsetMetadata对象，主要用于生产分配给消息的offset、
//              同时也是当前副本LEO(LogEndOffset)
// segmentBaseOffset：记录了activeSegment的baseOffset
// relativePositionInSegment字段记录了activeSegment的大小
@threadsafe
class Log(val dir: File,
          @volatile var config: LogConfig,
          @volatile var recoveryPoint: Long = 0L,
          scheduler: Scheduler,
          time: Time = SystemTime) extends Logging with KafkaMetricsGroup {

  import kafka.log.Log._

  /* A lock that guards all modifications to the log */
  private val lock = new Object

  /* last time it was flushed */
  private val lastflushedTime = new AtomicLong(time.milliseconds)

  def initFileSize() : Int = {
    if (config.preallocate)
      config.segmentSize
    else
      0
  }

  /* the actual segments of the log */
  // 跳表，随机化的数据结构，查找效率和红黑树差不多，但插入/删除操作却比红黑树简单的多
  // 线程安全的实现
  // 将每个LogSegment的baseOffset作为key，LogSegment对象作为value
  private val segments: ConcurrentNavigableMap[java.lang.Long, LogSegment] = new ConcurrentSkipListMap[java.lang.Long, LogSegment]
  loadSegments()

  /* Calculate the offset of the next message */
  @volatile var nextOffsetMetadata = new LogOffsetMetadata(activeSegment.nextOffset(), activeSegment.baseOffset, activeSegment.size.toInt)

  val topicAndPartition: TopicAndPartition = Log.parseTopicPartitionName(dir)

  info("Completed load of log %s with log end offset %d".format(name, logEndOffset))

  val tags = Map("topic" -> topicAndPartition.topic, "partition" -> topicAndPartition.partition.toString)

  // 注册Gauge对象
  newGauge("NumLogSegments",
    new Gauge[Int] {
      // 记录当前Log中的Segment对象个数
      def value = numberOfSegments
    },
    tags)

  newGauge("LogStartOffset",
    new Gauge[Long] {
      def value = logStartOffset
    },
    tags)

  newGauge("LogEndOffset",
    new Gauge[Long] {
      def value = logEndOffset
    },
    tags)

  newGauge("Size",
    new Gauge[Long] {
      def value = size
    },
    tags)

  /** The name of this log */
  def name  = dir.getName()

  /* Load the log segments from the log files on disk */
  // 删除.delete和.cleaned文件，cleaned后缀的文件表示是在日志压缩过程中宕机的，是不明确的
  // .delete是本来就要删除的
  // .swap是在swap过程宕机的，保存了日志压缩后的完整消息
  private def loadSegments() {
    // create the log directory if it doesn't exist
    dir.mkdirs()
    var swapFiles = Set[File]()

    // first do a pass through the files in the log directory and remove any temporary files
    // and find any interrupted swap operations
    for(file <- dir.listFiles if file.isFile) {
      if(!file.canRead)
        throw new IOException("Could not read file " + file)
      val filename = file.getName
      if(filename.endsWith(DeletedFileSuffix) || filename.endsWith(CleanedFileSuffix)) {
        // if the file ends in .deleted or .cleaned, delete it
        // 删除上面两个结尾的文件
        file.delete()
      } else if(filename.endsWith(SwapFileSuffix)) {
        // we crashed in the middle of a swap operation, to recover:
        // if a log, delete the .index file, complete the swap operation later
        // if an index just delete it, it will be rebuilt

        // 将.swap后缀去掉
        val baseName = new File(CoreUtils.replaceSuffix(file.getPath, SwapFileSuffix, ""))
        if(baseName.getPath.endsWith(IndexFileSuffix)) {
          // 去掉.swap发现是索引文件，直接删除
          file.delete()
        } else if(baseName.getPath.endsWith(LogFileSuffix)){
          // delete the index
          // 去掉.swap发现是日志文件，则需要进行恢复
          val index = new File(CoreUtils.replaceSuffix(baseName.getPath, LogFileSuffix, IndexFileSuffix))
          index.delete()
          // 添加到swapFiles集合中，带后面恢复
          swapFiles += file
        }
      }
    }

    // now do a second pass and load all the .log and .index files
    // 加载全部的日志文件和索引文件
    // 如果索引文件没有配对的日志文件，则删除索引文件
    // 如果日志文件没有对应的索引文件，则重新建立索引
    for(file <- dir.listFiles if file.isFile) {
      val filename = file.getName
      // 处理索引文件
      if(filename.endsWith(IndexFileSuffix)) {
        // if it is an index file, make sure it has a corresponding .log file
        val logFile = new File(file.getAbsolutePath.replace(IndexFileSuffix, LogFileSuffix))
        if(!logFile.exists) {
          warn("Found an orphaned index file, %s, with no corresponding log file.".format(file.getAbsolutePath))
          // 清理无效的索引文件
          file.delete()
        }
        // 处理日志文件
      } else if(filename.endsWith(LogFileSuffix)) {
        // if its a log file, load the corresponding log segment
        // 对于日志文件，LogSegment对象完成load
        val start = filename.substring(0, filename.length - LogFileSuffix.length).toLong
        val indexFile = Log.indexFilename(dir, start)
        // 创建LogSegment
        val segment = new LogSegment(dir = dir,
                                     startOffset = start,
                                     indexIntervalBytes = config.indexInterval,
                                     maxIndexSize = config.maxIndexSize,
                                     rollJitterMs = config.randomSegmentJitter,
                                     time = time,
                                     fileAlreadyExists = true)

        // 检测索引文件是否存在
        if(indexFile.exists()) {
          try {
            // 检测索引文件的完整性
              segment.index.sanityCheck()
          } catch {
            case e: java.lang.IllegalArgumentException =>
              warn("Found a corrupted index file, %s, deleting and rebuilding index...".format(indexFile.getAbsolutePath))
              indexFile.delete()
              segment.recover(config.maxMessageSize)
          }
        }
        else {
          error("Could not find index file corresponding to log file %s, rebuilding index...".format(segment.log.file.getAbsolutePath))
          // 如果没有对应的索引文件，则需要重创建索引文件
          segment.recover(config.maxMessageSize)
        }
        // 将LogSegment放入跳表中
        segments.put(start, segment)
      }
    }

    // Finally, complete any interrupted swap operations. To be crash-safe,
    // log files that are replaced by the swap segment should be renamed to .deleted
    // before the swap file is restored as the new segment file.
    // 处理步骤一记录的.swap文件
    for (swapFile <- swapFiles) {
      val logFile = new File(CoreUtils.replaceSuffix(swapFile.getPath, SwapFileSuffix, ""))
      val fileName = logFile.getName
      // 根据日志名称得到baseOffset
      val startOffset = fileName.substring(0, fileName.length - LogFileSuffix.length).toLong
      val indexFile = new File(CoreUtils.replaceSuffix(logFile.getPath, LogFileSuffix, IndexFileSuffix) + SwapFileSuffix)
      val index =  new OffsetIndex(indexFile, baseOffset = startOffset, maxIndexSize = config.maxIndexSize)
      // 创建LogSegment
      val swapSegment = new LogSegment(new FileMessageSet(file = swapFile),
                                       index = index,
                                       baseOffset = startOffset,
                                       indexIntervalBytes = config.indexInterval,
                                       rollJitterMs = config.randomSegmentJitter,
                                       time = time)
      info("Found log file %s from interrupted swap operation, repairing.".format(swapFile.getPath))
      // 重建索引文件并验证日志文件
      swapSegment.recover(config.maxMessageSize)
      // 查找swapSegment对应的日志压缩前的LogSegment集合
      val oldSegments = logSegments(swapSegment.baseOffset, swapSegment.nextOffset)
      // 在Log.replaceSegments方法中，将swapSegment对象加入到segments跳表管理
      // 将oldSegments集合中的全部LogSegment对象从segments删除掉并删除对应日志文件
      // 和索引文件，最后将.swap后缀名删除
      replaceSegments(swapSegment, oldSegments.toSeq, isRecoveredSwapFile = true)
    }

    // 对于空的log，需要创建activeSegment，保证log中至少有一个LogSegment，而对于非空的log，则需要进行恢复操作
    if(logSegments.size == 0) {
      // no existing segments, create a new mutable segment beginning at offset 0
      segments.put(0L, new LogSegment(dir = dir,
                                     startOffset = 0,
                                     indexIntervalBytes = config.indexInterval,
                                     maxIndexSize = config.maxIndexSize,
                                     rollJitterMs = config.randomSegmentJitter,
                                     time = time,
                                     fileAlreadyExists = false,
                                     initFileSize = this.initFileSize(),
                                     preallocate = config.preallocate))
    } else {
      // 进行log恢复操作
      recoverLog()
      // reset the index size of the currently active log segment to allow more entries
      activeSegment.index.resize(config.maxIndexSize)
    }

  }

  private def updateLogEndOffset(messageOffset: Long) {
    nextOffsetMetadata = new LogOffsetMetadata(messageOffset, activeSegment.baseOffset, activeSegment.size.toInt)
  }

  // 主要负责broker非正常关闭时导致的消息异常，需要将recoveryPoint到activeSegment中的所有消息进行验证
  // 截断验证失败的消息
  private def recoverLog() {
    // if we have the clean shutdown marker, skip recovery
    // 如果broker上次是正常关闭的，则不需要恢复，更新recoveryPoint
    if(hasCleanShutdownFile) {
      this.recoveryPoint = activeSegment.nextOffset
      return
    }

    // okay we need to actually recovery this log
    // 获取全部未刷新的LogSegment，即recoveryPoint之后的全部LogSegment
    val unflushed = logSegments(this.recoveryPoint, Long.MaxValue).iterator
    while(unflushed.hasNext) {
      val curr = unflushed.next
      info("Recovering unflushed segment %d in log %s.".format(curr.baseOffset, name))
      val truncatedBytes =
        try {
          // 重建索引文件并验证日志文件，验证失败的部分截断
          curr.recover(config.maxMessageSize)
        } catch {
          case e: InvalidOffsetException =>
            val startOffset = curr.baseOffset
            warn("Found invalid offset during recovery for log " + dir.getName +". Deleting the corrupt segment and " +
                 "creating an empty one with starting offset " + startOffset)
            curr.truncateTo(startOffset)
        }
      // LogSegment中有验证失败的消息
      if(truncatedBytes > 0) {
        // we had an invalid message, delete all remaining log
        warn("Corruption found in segment %d of log %s, truncating to offset %d.".format(curr.baseOffset, name, curr.nextOffset))
        // 将剩余的LogSegment全部删除
        unflushed.foreach(deleteSegment)
      }
    }
  }

  /**
   * Check if we have the "clean shutdown" file
   */
  private def hasCleanShutdownFile() = new File(dir.getParentFile, CleanShutdownFile).exists()

  /**
   * The number of segments in the log.
   * Take care! this is an O(n) operation.
   */
  def numberOfSegments: Int = segments.size

  /**
   * Close this log
   */
  def close() {
    debug("Closing log " + name)
    lock synchronized {
      for(seg <- logSegments)
        seg.close()
    }
  }

  /**
   * Append this message set to the active segment of the log, rolling over to a fresh segment if necessary.
   *
   * This method will generally be responsible for assigning offsets to the messages,
   * however if the assignOffsets=false flag is passed we will only check that the existing offsets are valid.
   *
   * @param messages The message set to append
   * @param assignOffsets Should the log assign offsets to this message set or blindly apply what it is given
   *
   * @throws KafkaStorageException If the append fails due to an I/O error.
   *
   * @return Information about the appended messages including the first and last offset.
   */
  def append(messages: ByteBufferMessageSet, assignOffsets: Boolean = true): LogAppendInfo = {
    // 步骤一：对ByteBufferMessageSet中的Message数据进行验证，返回LogAppendInfo对象
    // LogAppendInfo对象封装了ByteBufferMessageSet中第一个消息的offset，最后一个消息的offset
    // 生产者采用的压缩方式，追加到log的时间戳，服务端的压缩方式，外层消息的个数，通过验证的总字节数
    val appendInfo = analyzeAndValidateMessageSet(messages)

    // if we have any valid messages, append them to the log
    if (appendInfo.shallowCount == 0)
      return appendInfo

    // trim any invalid bytes or partial messages before appending it to the on-disk log
    // 步骤二：清除未验证通过的Message
    var validMessages = trimInvalidBytes(messages, appendInfo)

    try {
      // they are valid, insert them in the log
      // 加锁
      lock synchronized {

        // 判断是否需要分配offset，默认是需要的
        if (assignOffsets) {
          // assign offsets to the message set
          // 获取nextOffsetMetadata记录的messageOffset字段，从此处开始向后分配offset
          val offset = new LongRef(nextOffsetMetadata.messageOffset)
          // 使用firstOffset字段记录第一条消息的offset，并不受压缩消息的影响
          appendInfo.firstOffset = offset.value
          val now = time.milliseconds
          val (validatedMessages, messageSizesMaybeChanged) = try {
            // 步骤三：进行内部压缩消息的进一步验证，消息格式转换，跳转magic值，修改时间戳等
            // 并为message分配offset
            validMessages.validateMessagesAndAssignOffsets(offset,
                                                           now,
                                                           appendInfo.sourceCodec,
                                                           appendInfo.targetCodec,
                                                           config.compact,
                                                           config.messageFormatVersion.messageFormatVersion,
                                                           config.messageTimestampType,
                                                           config.messageTimestampDifferenceMaxMs)
          } catch {
            case e: IOException => throw new KafkaException("Error in validating messages while appending to log '%s'".format(name), e)
          }
          validMessages = validatedMessages
          // lastOffset记录最后一条数据，并不受压缩影响
          appendInfo.lastOffset = offset.value - 1
          if (config.messageTimestampType == TimestampType.LOG_APPEND_TIME)
          // 修改时间戳
            appendInfo.timestamp = now

          // re-validate message sizes if there's a possibility that they have changed (due to re-compression or message
          // format conversion)
          // 步骤四：若在validateMessagesAndAssignOffsets方法中修改了ByteBufferMessageSet的长度
          // 需要从新检测消息长度
          if (messageSizesMaybeChanged) {
            for (messageAndOffset <- validMessages.shallowIterator) {
              if (MessageSet.entrySize(messageAndOffset.message) > config.maxMessageSize) {
                // we record the original message set size instead of the trimmed size
                // to be consistent with pre-compression bytesRejectedRate recording
                BrokerTopicStats.getBrokerTopicStats(topicAndPartition.topic).bytesRejectedRate.mark(messages.sizeInBytes)
                BrokerTopicStats.getBrokerAllTopicsStats.bytesRejectedRate.mark(messages.sizeInBytes)
                throw new RecordTooLargeException("Message size is %d bytes which exceeds the maximum configured message size of %d."
                  .format(MessageSet.entrySize(messageAndOffset.message), config.maxMessageSize))
              }
            }
          }

        } else {
          // we are taking the offsets we are given
          // 边界检测
          if (!appendInfo.offsetsMonotonic || appendInfo.firstOffset < nextOffsetMetadata.messageOffset)
            throw new IllegalArgumentException("Out of order offsets found in " + messages)
        }

        // check messages set size may be exceed config.segmentSize
        if (validMessages.sizeInBytes > config.segmentSize) {
          throw new RecordBatchTooLargeException("Message set size is %d bytes which exceeds the maximum configured segment size of %d."
            .format(validMessages.sizeInBytes, config.segmentSize))
        }

        // maybe roll the log if this segment is full
        // 步骤五：获取activeSegment
        val segment = maybeRoll(validMessages.sizeInBytes)

        // now append to the log
        // 步骤六：追加消息
        segment.append(appendInfo.firstOffset, validMessages)

        // increment the log end offset
        // 步骤七：更新LED
        updateLogEndOffset(appendInfo.lastOffset + 1)

        trace("Appended message set to log %s with first offset: %d, next offset: %d, and messages: %s"
          .format(this.name, appendInfo.firstOffset, nextOffsetMetadata.messageOffset, validMessages))

        // 步骤八：检测为刷新到磁盘的数据达到一定阈值，则刷新
        if (unflushedMessages >= config.flushInterval)
          flush()

        appendInfo
      }
    } catch {
      case e: IOException => throw new KafkaStorageException("I/O exception in append to log '%s'".format(name), e)
    }
  }

  /**
   * Validate the following:
   * <ol>
   * <li> each message matches its CRC
   * <li> each message size is valid
   * </ol>
   *
   * Also compute the following quantities:
   * <ol>
   * <li> First offset in the message set
   * <li> Last offset in the message set
   * <li> Number of messages
   * <li> Number of valid bytes
   * <li> Whether the offsets are monotonically increasing
   * <li> Whether any compression codec is used (if many are used, then the last one is given)
   * </ol>
   */
  private def analyzeAndValidateMessageSet(messages: ByteBufferMessageSet): LogAppendInfo = {
    // 记录外层消息的数量
    var shallowMessageCount = 0
    // 记录通过验证的message的字节数之和
    var validBytesCount = 0
    // 记录第一条消息和最后一条消息的offset
    var firstOffset, lastOffset = -1L
    var sourceCodec: CompressionCodec = NoCompressionCodec
    // 标识生产者为消息分配的内部offset是否单调递增
    var monotonic = true
    // 使用浅层迭代器进行迭代，如果是压缩消息，并不会解压缩
    for(messageAndOffset <- messages.shallowIterator) {
      // update the first offset if on the first message
      // 记录第一条消息的offset，此时offset还是生产者分配的offset
      if(firstOffset < 0)
        firstOffset = messageAndOffset.offset
      // check that offsets are monotonically increasing
      // 判断内部的offset是否是递增
      if(lastOffset >= messageAndOffset.offset)
        monotonic = false
      // update the last offset seen
      // 记录最后一条offset
      lastOffset = messageAndOffset.offset

      val m = messageAndOffset.message

      // Check if the message sizes are valid.
      val messageSize = MessageSet.entrySize(m)
      // 检测message长度 失败抛异常
      if(messageSize > config.maxMessageSize) {
        BrokerTopicStats.getBrokerTopicStats(topicAndPartition.topic).bytesRejectedRate.mark(messages.sizeInBytes)
        BrokerTopicStats.getBrokerAllTopicsStats.bytesRejectedRate.mark(messages.sizeInBytes)
        throw new RecordTooLargeException("Message size is %d bytes which exceeds the maximum configured message size of %d."
          .format(messageSize, config.maxMessageSize))
      }

      // check the validity of the message by checking CRC
      // 检测message的CRC32校验码
      m.ensureValid()

      // 增加通过检测的外层消息树
      shallowMessageCount += 1
      validBytesCount += messageSize

      // 记录生产者采用的压缩方式
      val messageCodec = m.compressionCodec
      if(messageCodec != NoCompressionCodec)
        sourceCodec = messageCodec
    }

    // Apply broker-side compression if any
    // 记录服务端采用的压缩方式
    val targetCodec = BrokerCompressionCodec.getTargetCompressionCodec(config.compressionType, sourceCodec)

    // 返回LogAppendInfo
    LogAppendInfo(firstOffset, lastOffset, Message.NoTimestamp, sourceCodec, targetCodec, shallowMessageCount, validBytesCount, monotonic)
  }

  /**
   * Trim any invalid bytes from the end of this message set (if there are any)
   * @param messages The message set to trim
   * @param info The general information of the message set
   * @return A trimmed message set. This may be the same as what was passed in or it may not.
   */
  private def trimInvalidBytes(messages: ByteBufferMessageSet, info: LogAppendInfo): ByteBufferMessageSet = {
    val messageSetValidBytes = info.validBytes
    if(messageSetValidBytes < 0)
      throw new CorruptRecordException("Illegal length of message set " + messageSetValidBytes + " Message set cannot be appended to log. Possible causes are corrupted produce requests")
    if(messageSetValidBytes == messages.sizeInBytes) {
      messages
    } else {
      // trim invalid bytes
      val validByteBuffer = messages.buffer.duplicate()
      validByteBuffer.limit(messageSetValidBytes)
      new ByteBufferMessageSet(validByteBuffer)
    }
  }

  /**
   * Read messages from the log.
   *
   * @param startOffset The offset to begin reading at
   * @param maxLength The maximum number of bytes to read
   * @param maxOffset The offset to read up to, exclusive. (i.e. this offset NOT included in the resulting message set)
   *
   * @throws OffsetOutOfRangeException If startOffset is beyond the log end offset or before the base offset of the first segment.
   * @return The fetch data information including fetch starting offset metadata and messages read.
   */
  // 通过segments跳表，快速定位到读取的起始LogSegment并从中读取消息
  // 没有加锁，通过@volatile修饰nextOffsetMetadata字段
  def read(startOffset: Long, maxLength: Int, maxOffset: Option[Long] = None): FetchDataInfo = {
    trace("Reading %d bytes from offset %d in log %s of length %d bytes".format(maxLength, startOffset, name, size))

    // Because we don't use lock for reading, the synchronization is a little bit tricky.
    // We create the local variables to avoid race conditions with updates to the log.
    // 保存成局部变量
    val currentNextOffsetMetadata = nextOffsetMetadata
    val next = currentNextOffsetMetadata.messageOffset
    // 边界检测
    if(startOffset == next)
      return FetchDataInfo(currentNextOffsetMetadata, MessageSet.Empty)

    // 查找baseOffset小于startOffset且baseOffset最大的LogSegment
    var entry = segments.floorEntry(startOffset)

    // attempt to read beyond the log end offset is an error
    if(startOffset > next || entry == null)
      throw new OffsetOutOfRangeException("Request for offset %d but we only have log segments in the range %d to %d.".format(startOffset, segments.firstKey, next))

    // Do the read on the segment with a base offset less than the target offset
    // but if that segment doesn't contain any messages with an offset greater than that
    // continue to read from successive segments until we get some messages or we reach the end of the log
    while(entry != null) {
      // If the fetch occurs on the active segment, there might be a race condition where two fetch requests occur after
      // the message is appended but before the nextOffsetMetadata is updated. In that case the second fetch may
      // cause OffsetOutOfRangeException. To solve that, we cap the reading up to exposed position instead of the log
      // end of the active segment.
      val maxPosition = {
        // 处理读取activeSegment的情况
        if (entry == segments.lastEntry) {
          val exposedPos = nextOffsetMetadata.relativePositionInSegment.toLong
          // Check the segment again in case a new segment has just rolled out.
          if (entry != segments.lastEntry)
            // New log segment has rolled out, we can read up to the file end.
          // 写线程并发进行roll操作，变成了读取非activeSegment的场景
            entry.getValue.size
          else
            exposedPos
        } else {
          entry.getValue.size
        }
      }
      // 调用LogSegment.read方法
      val fetchInfo = entry.getValue.read(startOffset, maxOffset, maxLength, maxPosition)
      // 在此LogSegment中没有读取到数据，则继续读取下一个LogSegment
      if(fetchInfo == null) {
        entry = segments.higherEntry(entry.getKey)
      } else {
        return fetchInfo
      }
    }

    // okay we are beyond the end of the last segment with no data fetched although the start offset is in range,
    // this can happen when all messages with offset larger than start offsets have been deleted.
    // In this case, we will return the empty set with log end offset metadata
    // 找不到startOffset之间的数据
    FetchDataInfo(nextOffsetMetadata, MessageSet.Empty)
  }

  /**
   * Given a message offset, find its corresponding offset metadata in the log.
   * If the message offset is out of range, return unknown offset metadata
   */
  def convertToOffsetMetadata(offset: Long): LogOffsetMetadata = {
    try {
      val fetchDataInfo = read(offset, 1)
      fetchDataInfo.fetchOffsetMetadata
    } catch {
      case e: OffsetOutOfRangeException => LogOffsetMetadata.UnknownOffsetMetadata
    }
  }

  /**
   * Delete any log segments matching the given predicate function,
   * starting with the oldest segment and moving forward until a segment doesn't match.
   * @param predicate A function that takes in a single log segment and returns true iff it is deletable
   * @return The number of segments deleted
   */
  // 对于非activeSegment且符合predicate条件的都会删除
  def deleteOldSegments(predicate: LogSegment => Boolean): Int = {
    lock synchronized {
      //find any segments that match the user-supplied predicate UNLESS it is the final segment
      //and it is empty (since we would just end up re-creating it)
      // 获取activeSegment
      val lastEntry = segments.lastEntry
      val deletable =
        if (lastEntry == null) Seq.empty
        // 通过logSegments方法得到segments跳表中value集合的迭代器，之后循环检测LogSegment是否符合删除条件
        else logSegments.takeWhile(s => predicate(s) && (s.baseOffset != lastEntry.getValue.baseOffset || s.size > 0))
      val numToDelete = deletable.size
      if (numToDelete > 0) {
        // we must always have at least one segment, so if we are going to delete all the segments, create a new one first
        // 全部LogSegment都符合删除条件
        if (segments.size == numToDelete)
        // 删除前，先创建一个新的activeSegment
          roll()
        // remove the segments for lookups
        // 删除LogSegment
        deletable.foreach(deleteSegment(_))
      }
      numToDelete
    }
  }

  /**
   * The size of the log in bytes
   */
  def size: Long = logSegments.map(_.size).sum

   /**
   * The earliest message offset in the log
   */
  def logStartOffset: Long = logSegments.head.baseOffset

  /**
   * The offset metadata of the next message that will be appended to the log
   */
  def logEndOffsetMetadata: LogOffsetMetadata = nextOffsetMetadata

  /**
   *  The offset of the next message that will be appended to the log
   */
  def logEndOffset: Long = nextOffsetMetadata.messageOffset

  /**
   * Roll the log over to a new empty log segment if necessary.
   *
   * @param messagesSize The messages set size in bytes
   * logSegment will be rolled if one of the following conditions met
   * <ol>
   * <li> The logSegment is full
   * <li> The maxTime has elapsed
   * <li> The index is full
   * </ol>
   * @return The currently active segment after (perhaps) rolling to a new segment
   */
  private def maybeRoll(messagesSize: Int): LogSegment = {
    val segment = activeSegment
    // 条件一：当前activeSegment的日志大小加上本次待追加的消息集合大小，超过配置的LogSegment的最大长度
    if (segment.size > config.segmentSize - messagesSize ||
        // 条件二：当前activeSegment的寿命超过了配置的LogSegment最长存活时间
        segment.size > 0 && time.milliseconds - segment.created > config.segmentMs - segment.rollJitterMs ||
        // 条件三：索引文件满了
        segment.index.isFull) {
      debug("Rolling new log segment in %s (log_size = %d/%d, index_size = %d/%d, age_ms = %d/%d)."
            .format(name,
                    segment.size,
                    config.segmentSize,
                    segment.index.entries,
                    segment.index.maxEntries,
                    time.milliseconds - segment.created,
                    config.segmentMs - segment.rollJitterMs))
      // 创建activeSegment
      roll()
    } else {
      segment
    }
  }

  /**
   * Roll the log over to a new active segment starting with the current logEndOffset.
   * This will trim the index to the exact size of the number of entries it currently contains.
   * @return The newly rolled segment
   */
  def roll(): LogSegment = {
    val start = time.nanoseconds
    // 加锁
    lock synchronized {
      // 获取LEO
      val newOffset = logEndOffset
      // 新的日志文件名称是[LEO].log
      val logFile = logFilename(dir, newOffset)
      // 新的索引文件的文件名是[LEO].index
      val indexFile = indexFilename(dir, newOffset)
      for(file <- List(logFile, indexFile); if file.exists) {
        warn("Newly rolled segment file " + file.getName + " already exists; deleting it first")
        file.delete()
      }

      // segments的最后一个，也就是旧的activeSegment
      segments.lastEntry() match {
        case null =>
        case entry => {
          // 对索引文件和日志文件都进行截断，保证文件中只保存了有效字节
          entry.getValue.index.trimToValidSize()
          entry.getValue.log.trim()
        }
      }
      // 创建新的LogSegment
      val segment = new LogSegment(dir,
                                   startOffset = newOffset,
                                   indexIntervalBytes = config.indexInterval,
                                   maxIndexSize = config.maxIndexSize,
                                   rollJitterMs = config.randomSegmentJitter,
                                   time = time,
                                   fileAlreadyExists = false,
                                   initFileSize = initFileSize,
                                   preallocate = config.preallocate)
      // 将新创建的segment添加到segments这个跳表中
      val prev = addSegment(segment)
      // 之前存在baseOffset的LogSegment，抛出异常
      if(prev != null)
        throw new KafkaException("Trying to roll a new log segment for topic partition %s with start offset %d while it already exists.".format(name, newOffset))
      // We need to update the segment base offset and append position data of the metadata when log rolls.
      // The next offset should not change.

      // 更新nextOffsetMetadata,这次更新是为了更新其中记录的activeSegment.baseOffset和size，而LEO不变
      updateLogEndOffset(nextOffsetMetadata.messageOffset)
      // 执行flush操作
      // schedule an asynchronous flush of the old segment
      scheduler.schedule("flush-log", () => flush(newOffset), delay = 0L)

      info("Rolled new log segment for '" + name + "' in %.0f ms.".format((System.nanoTime - start) / (1000.0*1000.0)))
      // 返回activeSegment
      segment
    }
  }

  /**
   * The number of messages appended to the log since the last flush
   */
  def unflushedMessages() = this.logEndOffset - this.recoveryPoint

  /**
   * Flush all log segments
   */
  def flush(): Unit = flush(this.logEndOffset)

  /**
   * Flush log segments for all offsets up to offset-1
   * @param offset The offset to flush up to (non-inclusive); the new recovery point
   */
  // 将recoverPoint - LEO之间的消息数据刷新到磁盘上
  def flush(offset: Long) : Unit = {
    // offset之前的数据已经刷新了，所以不需要刷新
    if (offset <= this.recoveryPoint)
      return
    debug("Flushing log '" + name + " up to offset " + offset + ", last flushed: " + lastFlushTime + " current time: " +
          time.milliseconds + " unflushed = " + unflushedMessages)
    // logSegments()方法，通过推segments这个跳表操作，查找recoverPoint和offset之间的LogSegment对象
    for(segment <- logSegments(this.recoveryPoint, offset))
    // 调用LogSegment.flush方法
    //    底层分别调用日志文件和索引文件的flush方法，最终调用系统的fsync命令刷新磁盘
      segment.flush()
    lock synchronized {
      if(offset > this.recoveryPoint) {
        // 后移recoveryPoint
        this.recoveryPoint = offset
        // 修改lastflushedTime
        lastflushedTime.set(time.milliseconds)
      }
    }
  }

  /**
   * Completely delete this log directory and all contents from the file system with no delay
   */
  private[log] def delete() {
    lock synchronized {
      removeLogMetrics()
      logSegments.foreach(_.delete())
      segments.clear()
      Utils.delete(dir)
    }
  }

  /**
   * Truncate this log so that it ends with the greatest offset < targetOffset.
   * @param targetOffset The offset to truncate to, an upper bound on all offsets in the log after truncation is complete.
   */
  private[log] def truncateTo(targetOffset: Long) {
    info("Truncating log %s to offset %d.".format(name, targetOffset))
    if(targetOffset < 0)
      throw new IllegalArgumentException("Cannot truncate to a negative offset (%d).".format(targetOffset))
    if(targetOffset > logEndOffset) {
      info("Truncating %s to %d has no effect as the largest offset in the log is %d.".format(name, targetOffset, logEndOffset-1))
      return
    }
    lock synchronized {
      if(segments.firstEntry.getValue.baseOffset > targetOffset) {
        truncateFullyAndStartAt(targetOffset)
      } else {
        val deletable = logSegments.filter(segment => segment.baseOffset > targetOffset)
        deletable.foreach(deleteSegment(_))
        activeSegment.truncateTo(targetOffset)
        updateLogEndOffset(targetOffset)
        this.recoveryPoint = math.min(targetOffset, this.recoveryPoint)
      }
    }
  }

  /**
   *  Delete all data in the log and start at the new offset
   *  @param newOffset The new offset to start the log with
   */
  private[log] def truncateFullyAndStartAt(newOffset: Long) {
    debug("Truncate and start log '" + name + "' to " + newOffset)
    lock synchronized {
      val segmentsToDelete = logSegments.toList
      segmentsToDelete.foreach(deleteSegment(_))
      addSegment(new LogSegment(dir,
                                newOffset,
                                indexIntervalBytes = config.indexInterval,
                                maxIndexSize = config.maxIndexSize,
                                rollJitterMs = config.randomSegmentJitter,
                                time = time,
                                fileAlreadyExists = false,
                                initFileSize = initFileSize,
                                preallocate = config.preallocate))
      updateLogEndOffset(newOffset)
      this.recoveryPoint = math.min(newOffset, this.recoveryPoint)
    }
  }

  /**
   * The time this log is last known to have been fully flushed to disk
   */
  def lastFlushTime(): Long = lastflushedTime.get

  /**
   * The active segment that is currently taking appends
   */
  def activeSegment = segments.lastEntry.getValue

  /**
   * All the log segments in this log ordered from oldest to newest
   */
  def logSegments: Iterable[LogSegment] = {
    import JavaConversions._
    segments.values
  }

  /**
   * Get all segments beginning with the segment that includes "from" and ending with the segment
   * that includes up to "to-1" or the end of the log (if to > logEndOffset)
   */
  def logSegments(from: Long, to: Long): Iterable[LogSegment] = {
    import JavaConversions._
    lock synchronized {
      val floor = segments.floorKey(from)
      if(floor eq null)
        segments.headMap(to).values
      else
        segments.subMap(floor, true, to, false).values
    }
  }

  override def toString() = "Log(" + dir + ")"

  /**
   * This method performs an asynchronous log segment delete by doing the following:
   * <ol>
   *   <li>It removes the segment from the segment map so that it will no longer be used for reads.
   *   <li>It renames the index and log files by appending .deleted to the respective file name
   *   <li>It schedules an asynchronous delete operation to occur in the future
   * </ol>
   * This allows reads to happen concurrently without synchronization and without the possibility of physically
   * deleting a file while it is being read from.
   *
   * @param segment The log segment to schedule for deletion
   */
  private def deleteSegment(segment: LogSegment) {
    info("Scheduling log segment %d for log %s for deletion.".format(segment.baseOffset, name))
    lock synchronized {
      // 从segments集合中删除LogSegment对象
      segments.remove(segment.baseOffset)
      // 异步删除LogSegment对应的日志文件和索引文件
      asyncDeleteSegment(segment)
    }
  }

  /**
   * Perform an asynchronous delete on the given file if it exists (otherwise do nothing)
   * @throws KafkaStorageException if the file can't be renamed and still exists
   */
  private def asyncDeleteSegment(segment: LogSegment) {
    // 将日志文件和索引文件的后缀改成.delete
    segment.changeFileSuffixes("", Log.DeletedFileSuffix)
    def deleteSeg() {
      info("Deleting segment %d from log %s.".format(segment.baseOffset, name))
      // 删除日志文件和索引文件
      segment.delete()
    }
    // 方法作为定时任务放入scheduler线程池里等待执行
    scheduler.schedule("delete-file", deleteSeg, delay = config.fileDeleteDelayMs)
  }

  /**
   * Swap a new segment in place and delete one or more existing segments in a crash-safe manner. The old segments will
   * be asynchronously deleted.
   *
   * The sequence of operations is:
   * <ol>
   *   <li> Cleaner creates new segment with suffix .cleaned and invokes replaceSegments().
   *        If broker crashes at this point, the clean-and-swap operation is aborted and
   *        the .cleaned file is deleted on recovery in loadSegments().
   *   <li> New segment is renamed .swap. If the broker crashes after this point before the whole
   *        operation is completed, the swap operation is resumed on recovery as described in the next step.
   *   <li> Old segment files are renamed to .deleted and asynchronous delete is scheduled.
   *        If the broker crashes, any .deleted files left behind are deleted on recovery in loadSegments().
   *        replaceSegments() is then invoked to complete the swap with newSegment recreated from
   *        the .swap file and oldSegments containing segments which were not renamed before the crash.
   *   <li> Swap segment is renamed to replace the existing segment, completing this operation.
   *        If the broker crashes, any .deleted files which may be left behind are deleted
   *        on recovery in loadSegments().
   * </ol>
   *
   * @param newSegment The new log segment to add to the log
   * @param oldSegments The old log segments to delete from the log
   * @param isRecoveredSwapFile true if the new segment was created from a swap file during recovery after a crash
   */
  private[log] def replaceSegments(newSegment: LogSegment, oldSegments: Seq[LogSegment], isRecoveredSwapFile : Boolean = false) {
    lock synchronized {
      // need to do this in two phases to be crash safe AND do the delete asynchronously
      // if we crash in the middle of this we complete the swap in loadSegments()
      if (!isRecoveredSwapFile)
        newSegment.changeFileSuffixes(Log.CleanedFileSuffix, Log.SwapFileSuffix)
      addSegment(newSegment)

      // delete the old files
      for(seg <- oldSegments) {
        // remove the index entry
        if(seg.baseOffset != newSegment.baseOffset)
          segments.remove(seg.baseOffset)
        // delete segment
        asyncDeleteSegment(seg)
      }
      // okay we are safe now, remove the swap suffix
      newSegment.changeFileSuffixes(Log.SwapFileSuffix, "")
    }
  }

  /**
   * remove deleted log metrics
   */
  private[log] def removeLogMetrics(): Unit = {
    removeMetric("NumLogSegments", tags)
    removeMetric("LogStartOffset", tags)
    removeMetric("LogEndOffset", tags)
    removeMetric("Size", tags)
  }
  /**
   * Add the given segment to the segments in this log. If this segment replaces an existing segment, delete it.
   * @param segment The segment to add
   */
  def addSegment(segment: LogSegment) = this.segments.put(segment.baseOffset, segment)

}

/**
 * Helper functions for logs
 */
object Log {

  /** a log file */
  val LogFileSuffix = ".log"

  /** an index file */
  val IndexFileSuffix = ".index"

  /** a file that is scheduled to be deleted */
  val DeletedFileSuffix = ".deleted"

  /** A temporary file that is being used for log cleaning */
  val CleanedFileSuffix = ".cleaned"

  /** A temporary file used when swapping files into the log */
  val SwapFileSuffix = ".swap"

  /** Clean shutdown file that indicates the broker was cleanly shutdown in 0.8. This is required to maintain backwards compatibility
    * with 0.8 and avoid unnecessary log recovery when upgrading from 0.8 to 0.8.1 */
  /** TODO: Get rid of CleanShutdownFile in 0.8.2 */
  val CleanShutdownFile = ".kafka_cleanshutdown"

  /**
   * Make log segment file name from offset bytes. All this does is pad out the offset number with zeros
   * so that ls sorts the files numerically.
   * @param offset The offset to use in the file name
   * @return The filename
   */
  def filenamePrefixFromOffset(offset: Long): String = {
    val nf = NumberFormat.getInstance()
    nf.setMinimumIntegerDigits(20)
    nf.setMaximumFractionDigits(0)
    nf.setGroupingUsed(false)
    nf.format(offset)
  }

  /**
   * Construct a log file name in the given dir with the given base offset
   * @param dir The directory in which the log will reside
   * @param offset The base offset of the log file
   */
  def logFilename(dir: File, offset: Long) =
    new File(dir, filenamePrefixFromOffset(offset) + LogFileSuffix)

  /**
   * Construct an index file name in the given dir using the given base offset
   * @param dir The directory in which the log will reside
   * @param offset The base offset of the log file
   */
  def indexFilename(dir: File, offset: Long) =
    new File(dir, filenamePrefixFromOffset(offset) + IndexFileSuffix)


  /**
   * Parse the topic and partition out of the directory name of a log
   */
  def parseTopicPartitionName(dir: File): TopicAndPartition = {
    val name: String = dir.getName
    if (name == null || name.isEmpty || !name.contains('-')) {
      throwException(dir)
    }
    val index = name.lastIndexOf('-')
    val topic: String = name.substring(0, index)
    val partition: String = name.substring(index + 1)
    if (topic.length < 1 || partition.length < 1) {
      throwException(dir)
    }
    TopicAndPartition(topic, partition.toInt)
  }

  def throwException(dir: File) {
    throw new KafkaException("Found directory " + dir.getCanonicalPath + ", " +
      "'" + dir.getName + "' is not in the form of topic-partition\n" +
      "If a directory does not contain Kafka topic data it should not exist in Kafka's log " +
      "directory")
  }
}

