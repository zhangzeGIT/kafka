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
package kafka.utils.timer

import java.util.concurrent.{DelayQueue, Executors, ThreadFactory, TimeUnit}
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantReadWriteLock

import kafka.utils.threadsafe
import org.apache.kafka.common.utils.Utils

trait Timer {
  /**
    * Add a new task to this executor. It will be executed after the task's delay
    * (beginning from the time of submission)
    * @param timerTask the task to add
    */
  def add(timerTask: TimerTask): Unit

  /**
    * Advance the internal clock, executing any tasks whose expiration has been
    * reached within the duration of the passed timeout.
    * @param timeoutMs
    * @return whether or not any tasks were executed
    */
  def advanceClock(timeoutMs: Long): Boolean

  /**
    * Get the number of tasks pending execution
    * @return the number of tasks
    */
  def size: Int

  /**
    * Shutdown the timer service, leaving pending tasks unexecuted
    */
  def shutdown(): Unit
}

// Kafka中的定时器实现，它在TimeWheel的基础上添加了执行到期任务，阻塞等待最近到期任务的功能
@threadsafe
class SystemTimer(executorName: String,
                  tickMs: Long = 1,
                  wheelSize: Int = 20,
                  startMs: Long = System.currentTimeMillis) extends Timer {

  // timeout timer
  // JDK提供的固定线程数的线程池
  private[this] val taskExecutor = Executors.newFixedThreadPool(1, new ThreadFactory() {
    def newThread(runnable: Runnable): Thread =
      Utils.newThread("executor-"+executorName, runnable, false)
  })

  // 各个层级的时间轮共用的DelayQueue队列，主要作用是阻塞推进时间轮表针的进程
  private[this] val delayQueue = new DelayQueue[TimerTaskList]()
  // 各个层级时间轮共用的任务个数计数器
  private[this] val taskCounter = new AtomicInteger(0)
  // 层级时间轮中最底层的时间轮
  private[this] val timingWheel = new TimingWheel(
    tickMs = tickMs,
    wheelSize = wheelSize,
    startMs = startMs,
    taskCounter = taskCounter,
    delayQueue
  )

  // Locks used to protect data structures while ticking
  private[this] val readWriteLock = new ReentrantReadWriteLock()
  private[this] val readLock = readWriteLock.readLock()
  private[this] val writeLock = readWriteLock.writeLock()

  def add(timerTask: TimerTask): Unit = {
    readLock.lock()
    try {
      // 将TimerTask封装成TimerTaskEntry，并计算其到期时间
      addTimerTaskEntry(new TimerTaskEntry(timerTask, timerTask.delayMs + System.currentTimeMillis()))
    } finally {
      readLock.unlock()
    }
  }

  private def addTimerTaskEntry(timerTaskEntry: TimerTaskEntry): Unit = {
    // 向时间轮提交添加任务失败，任务可能已经到期或已取消
    if (!timingWheel.add(timerTaskEntry)) {
      // 将到期的任务提交到taskExecutor
      if (!timerTaskEntry.cancelled)
        taskExecutor.submit(timerTaskEntry.timerTask)
    }
  }

  // 将TimerTaskEntry从新提交到时间轮中
  private[this] val reinsert = (timerTaskEntry: TimerTaskEntry) => addTimerTaskEntry(timerTaskEntry)

  /*
   * Advances the clock if there is an expired bucket. If there isn't any expired bucket when called,
   * waits up to timeoutMs before giving up.
   */
  // 完成了时间轮表针的推进，同时对到期的TimerTaskList中任务进行处理
  // 如果TimerTaskList到期，当时其中的某些任务未到期，会将未到期任务进行降级，添加到低层次的时间轮中继续等待
  // 如果任务到期了，则提交到taskExecutor线程池中执行
  //
  def advanceClock(timeoutMs: Long): Boolean = {
    // 阻塞的等待
    var bucket = delayQueue.poll(timeoutMs, TimeUnit.MILLISECONDS)
    // 在阻塞期间，有TimerTaskList到期
    if (bucket != null) {
      writeLock.lock()
      try {
        while (bucket != null) {
          timingWheel.advanceClock(bucket.getExpiration())
          // 调用reinsert，尝试将bucket中的任务重新添加到时间轮，此过程并不一定是将任务提交给taskExecutor执行
          // 对于未到期的任务只是从原来的时间轮降级到下层的时间轮继续等待
          bucket.flush(reinsert)
          // 此poll方法不会阻塞
          bucket = delayQueue.poll()
        }
      } finally {
        writeLock.unlock()
      }
      true
    } else {
      false
    }
  }

  def size: Int = taskCounter.get

  override def shutdown() {
    taskExecutor.shutdown()
  }

}

