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

package kafka.server

import java.util.concurrent.locks.ReentrantLock

import kafka.cluster.BrokerEndPoint
import kafka.consumer.PartitionTopicInfo
import kafka.message.{MessageAndOffset, ByteBufferMessageSet}
import kafka.utils.{Pool, ShutdownableThread, DelayedItem}
import kafka.common.{KafkaException, ClientIdAndBroker, TopicAndPartition}
import kafka.metrics.KafkaMetricsGroup
import kafka.utils.CoreUtils.inLock
import org.apache.kafka.common.errors.CorruptRecordException
import org.apache.kafka.common.protocol.Errors
import AbstractFetcherThread._
import scala.collection.{mutable, Set, Map}
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

import com.yammer.metrics.core.Gauge

/**
 *  Abstract class for fetching data from multiple partitions from the same broker.
 */
abstract class AbstractFetcherThread(name: String,
                                     clientId: String,
                                     sourceBroker: BrokerEndPoint,
                                     fetchBackOffMs: Int = 0,
                                     isInterruptible: Boolean = true)
  extends ShutdownableThread(name, isInterruptible) {

  type REQ <: FetchRequest
  type PD <: PartitionData

  // 维护了TopicAndPartition与PartitionFetchState之间对应关系
  //    PartitionFetchState记录了对应分区的同步offset位置以及同步状态
  private val partitionMap = new mutable.HashMap[TopicAndPartition, PartitionFetchState] // a (topic, partition) -> partitionFetchState map
  private val partitionMapLock = new ReentrantLock
  private val partitionMapCond = partitionMapLock.newCondition()

  private val metricId = new ClientIdAndBroker(clientId, sourceBroker.host, sourceBroker.port)
  val fetcherStats = new FetcherStats(metricId)
  val fetcherLagStats = new FetcherLagStats(metricId)

  /* callbacks to be defined in subclass */

  // process fetched data
  def processPartitionData(topicAndPartition: TopicAndPartition, fetchOffset: Long, partitionData: PD)

  // handle a partition whose offset is out of range and return a new fetch offset
  def handleOffsetOutOfRange(topicAndPartition: TopicAndPartition): Long

  // deal with partitions with errors, potentially due to leadership changes
  def handlePartitionsWithErrors(partitions: Iterable[TopicAndPartition])

  protected def buildFetchRequest(partitionMap: Map[TopicAndPartition, PartitionFetchState]): REQ

  protected def fetch(fetchRequest: REQ): Map[TopicAndPartition, PD]

  override def shutdown(){
    initiateShutdown()
    inLock(partitionMapLock) {
      partitionMapCond.signalAll()
    }
    awaitShutdown()

    // we don't need the lock since the thread has finished shutdown and metric removal is safe
    fetcherStats.unregister()
    fetcherLagStats.unregister()
  }

  // 核心代码：根据partitionMap中维护的信息生成FetchRequest
  //          如果没有需要进行同步的分区，则会退避一会后重试
  //          如果有需要发送的FetchRequest，则执行processFetchRequest方法
  override def doWork() {

    val fetchRequest = inLock(partitionMapLock) {
      // 创建FetchRequest请求
      val fetchRequest = buildFetchRequest(partitionMap)
      if (fetchRequest.isEmpty) {
        trace("There are no active partitions. Back off for %d ms before sending a fetch request".format(fetchBackOffMs))
        // 没有FetchRequest，则退避一段时间后重试
        partitionMapCond.await(fetchBackOffMs, TimeUnit.MILLISECONDS)
      }
      fetchRequest
    }

    if (!fetchRequest.isEmpty)
      // 发送FetchRequest并处理FetchResponse
      processFetchRequest(fetchRequest)
  }

  // 定义了发送FetchRequest以及处理FetchResponse的步骤
  //    这些步骤由子类ReplicaFetchRequest具体实现
  private def processFetchRequest(fetchRequest: REQ) {
    val partitionsWithError = new mutable.HashSet[TopicAndPartition]
    var responseData: Map[TopicAndPartition, PD] = Map.empty

    try {
      trace("Issuing to broker %d of fetch request %s".format(sourceBroker.id, fetchRequest))
      // 发送FetchRequest并等待FetchResponse，fetch是抽象方法
      responseData = fetch(fetchRequest)
    } catch {
      case t: Throwable =>
        if (isRunning.get) {
          warn(s"Error in fetch $fetchRequest", t)
          inLock(partitionMapLock) {
            partitionsWithError ++= partitionMap.keys
            // there is an error occurred while fetching partitions, sleep a while
            // 线程退避一段时间
            partitionMapCond.await(fetchBackOffMs, TimeUnit.MILLISECONDS)
          }
        }
    }
    fetcherStats.requestRate.mark()

    // 处理FetchResponse
    if (responseData.nonEmpty) {
      // process fetched data
      inLock(partitionMapLock) {

        // 遍历每个TopicAndPartition对应的响应信息
        responseData.foreach { case (topicAndPartition, partitionData) =>
          val TopicAndPartition(topic, partitionId) = topicAndPartition
          partitionMap.get(topicAndPartition).foreach(currentPartitionFetchState =>
            // we append to the log if the current offset is defined and it is the same as the offset requested during fetch
            // 冲发送FetchRequest到收到FetchResponse这段同步时间内，Offset并未发生变化
            if (fetchRequest.offset(topicAndPartition) == currentPartitionFetchState.offset) {
              Errors.forCode(partitionData.errorCode) match {
                case Errors.NONE =>
                  try {
                    // 获取返回的消息集合
                    val messages = partitionData.toByteBufferMessageSet
                    val validBytes = messages.validBytes  // 验证
                    // 获取返回的最后一条消息的offset
                    val newOffset = messages.shallowIterator.toSeq.lastOption match {
                      case Some(m: MessageAndOffset) => m.nextOffset
                      case None => currentPartitionFetchState.offset
                    }
                    // 更新Fetch状态
                    partitionMap.put(topicAndPartition, new PartitionFetchState(newOffset))
                    fetcherLagStats.getAndMaybePut(topic, partitionId).lag = Math.max(0L, partitionData.highWatermark - newOffset)
                    fetcherStats.byteRate.mark(validBytes)
                    // Once we hand off the partition data to the subclass, we can't mess with it any more in this thread
                    // 将从leader副本获取的消息集合追加到Log中
                    processPartitionData(topicAndPartition, currentPartitionFetchState.offset, partitionData)
                  } catch {
                    case ime: CorruptRecordException =>
                      // we log the error and continue. This ensures two things
                      // 1. If there is a corrupt message in a topic partition, it does not bring the fetcher thread down and cause other topic partition to also lag
                      // 2. If the message is corrupt due to a transient state in the log (truncation, partial writes can cause this), we simply continue and
                      // should get fixed in the subsequent fetches
                      logger.error("Found invalid messages during fetch for partition [" + topic + "," + partitionId + "] offset " + currentPartitionFetchState.offset  + " error " + ime.getMessage)
                    case e: Throwable =>
                      throw new KafkaException("error processing data for partition [%s,%d] offset %d"
                        .format(topic, partitionId, currentPartitionFetchState.offset), e)
                  }
                case Errors.OFFSET_OUT_OF_RANGE =>
                  // 若follower副本请求的offset超出了Leader的LEO，返回此错误码
                  try {
                    // 生成新的offset
                    val newOffset = handleOffsetOutOfRange(topicAndPartition)
                    // 更新Fetch状态
                    partitionMap.put(topicAndPartition, new PartitionFetchState(newOffset))
                    error("Current offset %d for partition [%s,%d] out of range; reset offset to %d"
                      .format(currentPartitionFetchState.offset, topic, partitionId, newOffset))
                  } catch {
                    case e: Throwable =>
                      error("Error getting offset for partition [%s,%d] to broker %d".format(topic, partitionId, sourceBroker.id), e)
                      partitionsWithError += topicAndPartition
                  }
                case _ =>
                  // 其他错误码，进行收集后，由handlePartitionWithErrors方法处理
                  if (isRunning.get) {
                    error("Error for partition [%s,%d] to broker %d:%s".format(topic, partitionId, sourceBroker.id,
                      partitionData.exception.get))
                    partitionsWithError += topicAndPartition
                  }
              }
            })
        }
      }
    }

    if (partitionsWithError.nonEmpty) {
      debug("handling partitions with error for %s".format(partitionsWithError))
      // 抽象方法
      handlePartitionsWithErrors(partitionsWithError)
    }
  }

  // 对partitionMap字段进行增加
  def addPartitions(partitionAndOffsets: Map[TopicAndPartition, Long]) {
    partitionMapLock.lockInterruptibly()
    try {
      for ((topicAndPartition, offset) <- partitionAndOffsets) {
        // If the partitionMap already has the topic/partition, then do not update the map with the old offset
        // 检测分区是否已经存在
        if (!partitionMap.contains(topicAndPartition))
          partitionMap.put(
            topicAndPartition,
            if (PartitionTopicInfo.isOffsetInvalid(offset)) new PartitionFetchState(handleOffsetOutOfRange(topicAndPartition))
            else new PartitionFetchState(offset)
          )}
      // 唤醒当前fetcher线程，进行同步操作
      partitionMapCond.signalAll()
    } finally partitionMapLock.unlock()
  }

  def delayPartitions(partitions: Iterable[TopicAndPartition], delay: Long) {
    partitionMapLock.lockInterruptibly()
    try {
      for (partition <- partitions) {
        partitionMap.get(partition).foreach (currentPartitionFetchState =>
          // 检测分区同步状态
          if (currentPartitionFetchState.isActive)
          // 将分区对应的同步状态由激活状态设置为延时状态，延时时长为delay毫秒
            partitionMap.put(partition, new PartitionFetchState(currentPartitionFetchState.offset, new DelayedItem(delay)))
        )
      }
      // 唤醒fetcher线程
      partitionMapCond.signalAll()
    } finally partitionMapLock.unlock()
  }

  // 对partitionMap进行删除
  def removePartitions(topicAndPartitions: Set[TopicAndPartition]) {
    partitionMapLock.lockInterruptibly()
    try {
      topicAndPartitions.foreach { topicAndPartition =>
        partitionMap.remove(topicAndPartition)
        fetcherLagStats.unregister(topicAndPartition.topic, topicAndPartition.partition)
      }
    } finally partitionMapLock.unlock()
  }

  def partitionCount() = {
    partitionMapLock.lockInterruptibly()
    try partitionMap.size
    finally partitionMapLock.unlock()
  }

}

object AbstractFetcherThread {

  trait FetchRequest {
    def isEmpty: Boolean
    def offset(topicAndPartition: TopicAndPartition): Long
  }

  trait PartitionData {
    def errorCode: Short
    def exception: Option[Throwable]
    def toByteBufferMessageSet: ByteBufferMessageSet
    def highWatermark: Long
  }

}

object FetcherMetrics {
  val ConsumerLag = "ConsumerLag"
  val RequestsPerSec = "RequestsPerSec"
  val BytesPerSec = "BytesPerSec"
}

class FetcherLagMetrics(metricId: ClientIdTopicPartition) extends KafkaMetricsGroup {

  private[this] val lagVal = new AtomicLong(-1L)
  private[this] val tags = Map(
    "clientId" -> metricId.clientId,
    "topic" -> metricId.topic,
    "partition" -> metricId.partitionId.toString)

  newGauge(FetcherMetrics.ConsumerLag,
    new Gauge[Long] {
      def value = lagVal.get
    },
    tags
  )

  def lag_=(newLag: Long) {
    lagVal.set(newLag)
  }

  def lag = lagVal.get

  def unregister() {
    removeMetric(FetcherMetrics.ConsumerLag, tags)
  }
}

class FetcherLagStats(metricId: ClientIdAndBroker) {
  private val valueFactory = (k: ClientIdTopicPartition) => new FetcherLagMetrics(k)
  val stats = new Pool[ClientIdTopicPartition, FetcherLagMetrics](Some(valueFactory))

  def getAndMaybePut(topic: String, partitionId: Int): FetcherLagMetrics = {
    stats.getAndMaybePut(new ClientIdTopicPartition(metricId.clientId, topic, partitionId))
  }

  def unregister(topic: String, partitionId: Int) {
    val lagMetrics = stats.remove(new ClientIdTopicPartition(metricId.clientId, topic, partitionId))
    if (lagMetrics != null) lagMetrics.unregister()
  }

  def unregister() {
    stats.keys.toBuffer.foreach { key: ClientIdTopicPartition =>
      unregister(key.topic, key.partitionId)
    }
  }
}

class FetcherStats(metricId: ClientIdAndBroker) extends KafkaMetricsGroup {
  val tags = Map("clientId" -> metricId.clientId,
    "brokerHost" -> metricId.brokerHost,
    "brokerPort" -> metricId.brokerPort.toString)

  val requestRate = newMeter(FetcherMetrics.RequestsPerSec, "requests", TimeUnit.SECONDS, tags)

  val byteRate = newMeter(FetcherMetrics.BytesPerSec, "bytes", TimeUnit.SECONDS, tags)

  def unregister() {
    removeMetric(FetcherMetrics.RequestsPerSec, tags)
    removeMetric(FetcherMetrics.BytesPerSec, tags)
  }

}

case class ClientIdTopicPartition(clientId: String, topic: String, partitionId: Int) {
  override def toString = "%s-%s-%d".format(clientId, topic, partitionId)
}

/**
  * case class to keep partition offset and its state(active , inactive)
  */
case class PartitionFetchState(offset: Long, delay: DelayedItem) {

  def this(offset: Long) = this(offset, new DelayedItem(0))

  def isActive: Boolean = { delay.getDelay(TimeUnit.MILLISECONDS) == 0 }

  override def toString = "%d-%b".format(offset, isActive)
}
