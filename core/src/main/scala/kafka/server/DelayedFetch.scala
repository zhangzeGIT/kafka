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

import java.util.concurrent.TimeUnit

import kafka.api.FetchResponsePartitionData
import kafka.api.PartitionFetchInfo
import kafka.common.TopicAndPartition
import kafka.metrics.KafkaMetricsGroup
import org.apache.kafka.common.errors.{NotLeaderForPartitionException, UnknownTopicOrPartitionException}

import scala.collection._

// start：前面读取Log时，已经读取到的offset位置。
// info：FetchRequest携带的一些信息，主要是请求的offset以及读取最大字节数
case class FetchPartitionStatus(startOffsetMetadata: LogOffsetMetadata, fetchInfo: PartitionFetchInfo) {

  override def toString = "[startOffsetMetadata: " + startOffsetMetadata + ", " +
                          "fetchInfo: " + fetchInfo + "]"
}

/**
 * The fetch metadata maintained by the delayed fetch operation
 */
// fetchMinBytes字段记录了需要读取的最小字节数
// fetchPartitionStatus记录了每个分区的FetchPartitionStatus
case class FetchMetadata(fetchMinBytes: Int,
                         fetchOnlyLeader: Boolean,
                         fetchOnlyCommitted: Boolean,
                         isFromFollower: Boolean,
                         fetchPartitionStatus: Map[TopicAndPartition, FetchPartitionStatus]) {

  override def toString = "[minBytes: " + fetchMinBytes + ", " +
                          "onlyLeader:" + fetchOnlyLeader + ", "
                          "onlyCommitted: " + fetchOnlyCommitted + ", "
                          "partitionStatus: " + fetchPartitionStatus + "]"
}
/**
 * A delayed fetch operation that can be created by the replica manager and watched
 * in the fetch operation purgatory
 */
// delayMs：延迟操作的延迟时间
// fetchMetadata：FetchMetadata对象，记录分区相关状态，主要用于判断DelayedProduce是否满足执行条件
// responseCallback：满足任务条件或到期执行时，在DelayedFetch.onComplete方法中调用的函数
//     主要功能是创建FetchResponse并添加到RequestChannels中对应的responseQueue队列中
class DelayedFetch(delayMs: Long,
                   fetchMetadata: FetchMetadata,
                   replicaManager: ReplicaManager,
                   responseCallback: Map[TopicAndPartition, FetchResponsePartitionData] => Unit)
  extends DelayedOperation(delayMs) {

  /**
   * The operation can be completed if:
   *
   * Case A: This broker is no longer the leader for some partitions it tries to fetch
   * Case B: This broker does not know of some partitions it tries to fetch
   * Case C: The fetch offset locates not on the last segment of the log
   * Case D: The accumulated bytes from all the fetching partitions exceeds the minimum bytes
   *
   * Upon completion, should return whatever data is available for each valid partition
   */
  // 判断是否满足DelayedFetch的执行条件
  override def tryComplete() : Boolean = {
    var accumulatedSize = 0
    // 遍历fetchMetadata中的所有partition的状态
    fetchMetadata.fetchPartitionStatus.foreach {
      case (topicAndPartition, fetchStatus) =>
        // 获取前面读取Log时的结实位置
        val fetchOffset = fetchStatus.startOffsetMetadata
        try {
          if (fetchOffset != LogOffsetMetadata.UnknownOffsetMetadata) {
            // 查找分区的leader副本，找不到抛异常
            val replica = replicaManager.getLeaderReplicaIfLocal(topicAndPartition.topic, topicAndPartition.partition)
            // 根据FetchRequest 请求的来源设置能读取的最大offset值，
            // 消费者对应的endOffset是HW，而Follower副本对应的endOffset是LED
            val endOffset =
              if (fetchMetadata.fetchOnlyCommitted)
                replica.highWatermark
              else
                replica.logEndOffset

            // Go directly to the check for Case D if the message offsets are the same. If the log segment
            // has just rolled, then the high watermark offset will remain the same but be on the old segment,
            // which would incorrectly be seen as an instance of Case C.

            // 检查上次读取后endOffset是否发生变化
            //    如果 没改变，之前读不到足够的数据，现在还是读不到，即任务条件依然不满足
            //    如果变了，继续下面的检查，看是否真正满足任务执行条件
            if (endOffset.messageOffset != fetchOffset.messageOffset) {
              // 条件：开始读取的offset不在activeSegment中，此时可能发生了Log截断，也有可能发生了roll操作产生了新的activeSegment
              if (endOffset.onOlderSegment(fetchOffset)) {
                // Case C, this can happen when the new fetch operation is on a truncated leader
                debug("Satisfying fetch %s since it is fetching later segments of partition %s.".format(fetchMetadata, topicAndPartition))
                // 此时，endOffset出现减少的情况，跑到baseOffset较小的Segment上了，
                // 可能Leader副本的Log出现了truncate操作
                return forceComplete()
              } else if (fetchOffset.onOlderSegment(endOffset)) {
                // Case C, this can happen when the fetch operation is falling behind the current segment
                // or the partition has just rolled a new segment
                debug("Satisfying fetch %s immediately since it is fetching older segments.".format(fetchMetadata))
                // 此时，fetchOffset虽然依然出现在endOffset之前，但是产生了新的activeSegment
                // fetchOffset在较旧的LogSegment，而endOffset在activeSegment
                return forceComplete()
              } else if (fetchOffset.messageOffset < endOffset.messageOffset) {
                // we need take the partition fetch size as upper bound when accumulating the bytes
                // endOffset和fetchOffset依然在同一个LogSegment中，且endOffset向后移动，那就尝试计算累计的字节数
                accumulatedSize += math.min(endOffset.positionDiff(fetchOffset), fetchStatus.fetchInfo.fetchSize)
              }
            }
          }
        } catch {
          case utpe: UnknownTopicOrPartitionException => // Case B
            debug("Broker no longer know of %s, satisfy %s immediately".format(topicAndPartition, fetchMetadata))
            return forceComplete()
          case nle: NotLeaderForPartitionException =>  // Case A
            debug("Broker is no longer the leader of %s, satisfy %s immediately".format(topicAndPartition, fetchMetadata))
            return forceComplete()
        }
    }

    // Case D
    // 累计读取的字节数超过了最小字节数限制
    if (accumulatedSize >= fetchMetadata.fetchMinBytes)
    // 调用onComplete方法，在其中会重新读取数据
      forceComplete()
    else
      false
  }

  override def onExpiration() {
    if (fetchMetadata.isFromFollower)
      DelayedFetchMetrics.followerExpiredRequestMeter.mark()
    else
      DelayedFetchMetrics.consumerExpiredRequestMeter.mark()
  }

  /**
   * Upon completion, read whatever data is available and pass to the complete callback
   */
  override def onComplete() {
    // 重新从Log中读取数据
    val logReadResults = replicaManager.readFromLocalLog(fetchMetadata.fetchOnlyLeader,
      fetchMetadata.fetchOnlyCommitted,
      fetchMetadata.fetchPartitionStatus.mapValues(status => status.fetchInfo))

    // 将结果进行封装
    val fetchPartitionData = logReadResults.mapValues(result =>
      FetchResponsePartitionData(result.errorCode, result.hw, result.info.messageSet))

    // 调用回调函数
    responseCallback(fetchPartitionData)
  }
}

object DelayedFetchMetrics extends KafkaMetricsGroup {
  private val FetcherTypeKey = "fetcherType"
  val followerExpiredRequestMeter = newMeter("ExpiresPerSec", "requests", TimeUnit.SECONDS, tags = Map(FetcherTypeKey -> "follower"))
  val consumerExpiredRequestMeter = newMeter("ExpiresPerSec", "requests", TimeUnit.SECONDS, tags = Map(FetcherTypeKey -> "consumer"))
}

