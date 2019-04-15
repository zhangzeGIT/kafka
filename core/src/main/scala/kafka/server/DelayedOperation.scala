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

import kafka.utils._
import kafka.utils.timer._
import kafka.utils.CoreUtils.{inReadLock, inWriteLock}
import kafka.metrics.KafkaMetricsGroup

import java.util.LinkedList
import java.util.concurrent._
import java.util.concurrent.atomic._
import java.util.concurrent.locks.ReentrantReadWriteLock

import org.apache.kafka.common.utils.Utils

import scala.collection._

import com.yammer.metrics.core.Gauge


/**
 * An operation whose processing needs to be delayed for at most the given delayMs. For example
 * a delayed produce operation could be waiting for specified number of acks; or
 * a delayed fetch operation could be waiting for a given number of bytes to accumulate.
 *
 * The logic upon completing a delayed operation is defined in onComplete() and will be called exactly once.
 * Once an operation is completed, isCompleted() will return true. onComplete() can be triggered by either
 * forceComplete(), which forces calling onComplete() after delayMs if the operation is not yet completed,
 * or tryComplete(), which first checks if the operation can be completed or not now, and if yes calls
 * forceComplete().
 *
 * A subclass of DelayedOperation needs to provide an implementation of both onComplete() and tryComplete().
 */
// delayMs表示延迟操作的延迟时间
abstract class DelayedOperation(override val delayMs: Long) extends TimerTask with Logging {

  // 标识了此DelayedOperation是否完成
  private val completed = new AtomicBoolean(false)

  /*
   * Force completing the delayed operation, if not already completed.
   * This function can be triggered when
   *
   * 1. The operation has been verified to be completable inside tryComplete()
   * 2. The operation has expired and hence needs to be completed right now
   *
   * Return true iff the operation is completed by the caller: note that
   * concurrent threads can try to complete the same operation, but only
   * the first thread will succeed in completing the operation and return
   * true, others will still return false
   */
  def forceComplete(): Boolean = {
    // 根据completed字段的值判断延迟操作是否已经完成
    if (completed.compareAndSet(false, true)) {
      // cancel the timeout timer
      // 调用的是TimerTask.cancel方法，将其从TimerTaskList中删除
      cancel()
      // 延迟操作的真正逻辑
      // 例如：DelayProduce就是向客户端返回ProduceResponse响应
      onComplete()
      true
    } else {
      false
    }
  }

  /**
   * Check if the delayed operation is already completed
   */
  def isCompleted(): Boolean = completed.get()

  /**
   * Call-back to execute when a delayed operation gets expired and hence forced to complete.
   */
  // DelayedOperation到期时执行的具体逻辑
  def onExpiration(): Unit

  /**
   * Process for completing an operation; This function needs to be defined
   * in subclasses and will be called exactly once in forceComplete()
   */
  // 整个生命周期只调用一次
  def onComplete(): Unit

  /*
   * Try to complete the delayed operation by first checking if the operation
   * can be completed by now. If yes execute the completion logic by calling
   * forceComplete() and return true iff forceComplete returns true; otherwise return false
   *
   * This function needs to be defined in subclasses
   */
  // 条件是否满足，满足调用forceComplete完成延迟操作
  def tryComplete(): Boolean

  /*
   * run() method defines a task that is executed on timeout
   */
  // 到期时会提交到SystemTimer.taskExecutor线程池中执行，
  // 调用forceComplete方法完成延迟操作，然后调用onExpiration方法执行延迟操作到期执行的相关代码
  override def run(): Unit = {
    if (forceComplete())
      onExpiration()
  }
}

object DelayedOperationPurgatory {

  def apply[T <: DelayedOperation](purgatoryName: String,
                                   brokerId: Int = 0,
                                   purgeInterval: Int = 1000): DelayedOperationPurgatory[T] = {
    val timer = new SystemTimer(purgatoryName)
    new DelayedOperationPurgatory[T](purgatoryName, timer, brokerId, purgeInterval)
  }

}

/**
 * A helper purgatory class for bookkeeping delayed operations with a timeout, and expiring timed out operations.
 */
// timeoutTimer：SystemTimer对象
class DelayedOperationPurgatory[T <: DelayedOperation](purgatoryName: String,
                                                       timeoutTimer: Timer,
                                                       brokerId: Int = 0,
                                                       purgeInterval: Int = 1000,
                                                       reaperEnabled: Boolean = true)
        extends Logging with KafkaMetricsGroup {

  /* a list of operation watching keys */
  // 用于管理DelayedOperation，pool底层是ConcurrentHashMap
  private val watchersForKey = new Pool[Any, Watchers](Some((key: Any) => new Watchers(key)))

  private val removeWatchersLock = new ReentrantReadWriteLock()

  // the number of estimated total operations in the purgatory
  // 记录DelayedOperation个数
  private[this] val estimatedTotalOperations = new AtomicInteger(0)

  /* background thread expiring operations that have timed out */
  // ShutdownableThread线程对象，推进时间轮指针，定期清理watchersForKey中已经完成的DelayedOperation个数
  private val expirationReaper = new ExpiredOperationReaper()

  private val metricsTags = Map("delayedOperation" -> purgatoryName)

  newGauge(
    "PurgatorySize",
    new Gauge[Int] {
      def value = watched()
    },
    metricsTags
  )

  newGauge(
    "NumDelayedOperations",
    new Gauge[Int] {
      def value = delayed()
    },
    metricsTags
  )

  if (reaperEnabled)
    expirationReaper.start()

  /**
   * Check if the operation can be completed, if not watch it based on the given watch keys
   *
   * Note that a delayed operation can be watched on multiple keys. It is possible that
   * an operation is completed after it has been added to the watch list for some, but
   * not all of the keys. In this case, the operation is considered completed and won't
   * be added to the watch list of the remaining keys. The expiration reaper thread will
   * remove this operation from any watcher list in which the operation exists.
   *
   * @param operation the delayed operation to be checked
   * @param watchKeys keys for bookkeeping the operation
   * @return true iff the delayed operations can be completed by the caller
   */
  // 检测DO是否完成，未完成则添加watchersForKey以及SystemTimer中
  def tryCompleteElseWatch(operation: T, watchKeys: Seq[Any]): Boolean = {
    assert(watchKeys.size > 0, "The watch key list can't be empty")

    // The cost of tryComplete() is typically proportional to the number of keys. Calling
    // tryComplete() for each key is going to be expensive if there are many keys. Instead,
    // we do the check in the following way. Call tryComplete(). If the operation is not completed,
    // we just add the operation to all keys. Then we call tryComplete() again. At this time, if
    // the operation is still not completed, we are guaranteed that it won't miss any future triggering
    // event since the operation is already on the watcher list for all keys. This does mean that
    // if the operation is completed (by another thread) between the two tryComplete() calls, the
    // operation is unnecessarily added for watch. However, this is a less severe issue since the
    // expire reaper will clean it up periodically.

    // 调用DO.tryComplete方法，尝试完成延迟操作
    var isCompletedByMe = operation synchronized operation.tryComplete()
    // 已完成，直接返回
    if (isCompletedByMe)
      return true

    var watchCreated = false
    // 传入的key可能有多个，每个key表示一个DO关心的条件
    // 将DO添加到所有KEY对应的Watchers中
    for(key <- watchKeys) {
      // If the operation is already completed, stop adding it to the rest of the watcher list.
      // 添加过程中，若已经被其他线程完成，则放弃后续添加过程，ExpiredOperationReaper
      // 线程会定期清理watchersForKey，所以这里不需要清理之前添加的key
      if (operation.isCompleted())
        return false
      // 将DO添加到watchersForKey中对应的watchers中
      watchForOperation(key, operation)

      if (!watchCreated) {
        watchCreated = true
        // 增加estimated的值
        estimatedTotalOperations.incrementAndGet()
      }
    }

    // 第二次尝试完成此DO，如果成功，直接返回
    isCompletedByMe = operation synchronized operation.tryComplete()
    if (isCompletedByMe)
      return true

    // if it cannot be completed by now and hence is watched, add to the expire queue also
    // 执行到这里，可以保证，此DO不会错过任何key上触发的checkAndComplete
    // 将DO提交到SystemTimer
    if (! operation.isCompleted()) {
      timeoutTimer.add(operation)
      // 再次检测完成情况
      if (operation.isCompleted()) {
        // cancel the timer task
        // 如果已经完成，将其从SystemTimer中删除
        operation.cancel()
      }
    }

    false
  }

  /**
   * Check if some some delayed operations can be completed with the given watch key,
   * and if yes complete them.
   *
   * @return the number of completed operations during this process
   */
  // 通过传入的key，尝试执行对应Watchers中的DelayedOperation
  def checkAndComplete(key: Any): Int = {
    val watchers = inReadLock(removeWatchersLock) { watchersForKey.get(key) }
    if(watchers == null)
      0
    else
      watchers.tryCompleteWatched()
  }

  /**
   * Return the total size of watch lists the purgatory. Since an operation may be watched
   * on multiple lists, and some of its watched entries may still be in the watch lists
   * even when it has been completed, this number may be larger than the number of real operations watched
   */
  def watched() = allWatchers.map(_.watched).sum

  /**
   * Return the number of delayed operations in the expiry queue
   */
  def delayed() = timeoutTimer.size

  /*
   * Return all the current watcher lists,
   * note that the returned watchers may be removed from the list by other threads
   */
  private def allWatchers = inReadLock(removeWatchersLock) { watchersForKey.values }

  /*
   * Return the watch list of the given key, note that we need to
   * grab the removeWatchersLock to avoid the operation being added to a removed watcher list
   */
  private def watchForOperation(key: Any, operation: T) {
    inReadLock(removeWatchersLock) {
      val watcher = watchersForKey.getAndMaybePut(key)
      watcher.watch(operation)
    }
  }

  /*
   * Remove the key from watcher lists if its list is empty
   */
  private def removeKeyIfEmpty(key: Any, watchers: Watchers) {
    inWriteLock(removeWatchersLock) {
      // if the current key is no longer correlated to the watchers to remove, skip
      if (watchersForKey.get(key) != watchers)
        return

      if (watchers != null && watchers.watched == 0) {
        watchersForKey.remove(key)
      }
    }
  }

  /**
   * Shutdown the expire reaper thread
   */
  def shutdown() {
    if (reaperEnabled)
      expirationReaper.shutdown()
    timeoutTimer.shutdown()
  }

  /**
   * A linked list of watched delayed operations based on some key
   */
  private class Watchers(val key: Any) {

    // 管理DelayedOperation的LinkedList队列
    private[this] val operations = new LinkedList[T]()

    def watched: Int = operations synchronized operations.size

    // add the element to watch
    // 将DelayedOperation添加到operations队列中
    def watch(t: T) {
      operations synchronized operations.add(t)
    }

    // traverse the list and try to complete some watched elements
    // 遍历operations队列，对于未完成DelayedOperation执行tryComplete方法
    // 对已完成的对象移除
    // 如果operations队列为空，则将Watches从DelayedOperationPurgatory.watchersForKey中删除
    def tryCompleteWatched(): Int = {

      var completed = 0
      operations synchronized {
        val iter = operations.iterator()
        while (iter.hasNext) {
          val curr = iter.next()
          if (curr.isCompleted) {
            // another thread has completed this operation, just remove it
            iter.remove()
          } else if (curr synchronized curr.tryComplete()) {
            completed += 1
            iter.remove()
          }
        }
      }

      if (operations.size == 0)
        removeKeyIfEmpty(key, this)

      completed
    }

    // traverse the list and purge elements that are already completed by others
    // 清理operations队列，将已经完成的DelayedOperation从operations队列中移除
    // 如果队列为空，则将Watchers从watchersForKey集合中移除
    def purgeCompleted(): Int = {
      var purged = 0
      operations synchronized {
        val iter = operations.iterator()
        while (iter.hasNext) {
          val curr = iter.next()
          if (curr.isCompleted) {
            iter.remove()
            purged += 1
          }
        }
      }

      if (operations.size == 0)
        removeKeyIfEmpty(key, this)

      purged
    }
  }

  def advanceClock(timeoutMs: Long) {
    // 尝试推进时间轮的指针
    timeoutTimer.advanceClock(timeoutMs)

    // Trigger a purge if the number of completed but still being watched operations is larger than
    // the purge threshold. That number is computed by the difference btw the estimated total number of
    // operations and the number of pending delayed operations.
    // DO到期后背SystemTimer.taskExecutor完成后，不会通知DOP删除DO
    // 当DOP与SystemTimer中的DO数量相差到一个阈值时，会调用purgeCompleted发放
    if (estimatedTotalOperations.get - delayed > purgeInterval) {
      // now set estimatedTotalOperations to delayed (the number of pending operations) since we are going to
      // clean up watchers. Note that, if more operations are completed during the clean up, we may end up with
      // a little overestimated total number of operations.

      // 更新estimatedTotalOperations
      estimatedTotalOperations.getAndSet(delayed)
      debug("Begin purging watch lists")
      // 调用purgeCompleted方法清理已完成的DO
      val purged = allWatchers.map(_.purgeCompleted()).sum
      debug("Purged %d elements from watch lists.".format(purged))
    }
  }

  /**
   * A background reaper to expire delayed operations that have timed out
   */
  private class ExpiredOperationReaper extends ShutdownableThread(
    "ExpirationReaper-%d".format(brokerId),
    false) {

    override def doWork() {
      advanceClock(200L)
    }
  }
}
