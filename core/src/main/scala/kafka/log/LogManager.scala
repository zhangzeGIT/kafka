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

import java.io._
import java.util.concurrent.TimeUnit

import kafka.utils._

import scala.collection._
import kafka.common.{KafkaException, TopicAndPartition}
import kafka.server.{BrokerState, OffsetCheckpoint, RecoveringFromUncleanShutdown}
import java.util.concurrent.{ExecutionException, ExecutorService, Executors, Future}

/**
 * The entry point to the kafka log management subsystem. The log manager is responsible for log creation, retrieval, and cleaning.
 * All read and write operations are delegated to the individual log instances.
 * 
 * The log manager maintains logs in one or more directories. New logs are created in the data directory
 * with the fewest logs. No attempt is made to move partitions after the fact or balance based on
 * size or I/O rate.
 * 
 * A background thread handles log retention by periodically truncating excess log segments.
 */
// logDirs：log目录集合，log.dirs项指定的多个目录，每个目录下可以创建多个Log，每次创建log时，选择log最少的目录
// ioThread：为完成Log加载，每个log目录下分配指定的线程进行加载
// scheduler：KafkaScheduler对象，用于执行周期任务的线程池
// 启动了三个周期性的后台任务以及Cleaner线程（可能不止一个）
// 分别是log-flusher任务，log-retention日志保留任务，recovery-point-checkpoint检查点刷新任务
@threadsafe
class LogManager(val logDirs: Array[File],
                 val topicConfigs: Map[String, LogConfig],
                 val defaultConfig: LogConfig,
                 val cleanerConfig: CleanerConfig,
                 ioThreads: Int,
                 val flushCheckMs: Long,
                 val flushCheckpointMs: Long,
                 val retentionCheckMs: Long,
                 scheduler: Scheduler,
                 val brokerState: BrokerState,
                 private val time: Time) extends Logging {
  val RecoveryPointCheckpointFile = "recovery-point-offset-checkpoint"
  val LockFile = ".lock"
  val InitialTaskDelayMs = 30*1000
  private val logCreationOrDeletionLock = new Object
  // logs：用于管理TopicAndPartition与Log之间的对应关系
  private val logs = new Pool[TopicAndPartition, Log]()

  createAndValidateLogDirs(logDirs)
  // FileLock锁集合
  private val dirLocks = lockLogDirs(logDirs)
  // 管理每个log目录与其下的RecoveryPointCheckPoint文件之间的映射关系
  // 每个log目录创建一个对应的RecoveryPointCheckpoint文件
  // 此map的value是OffsetCheckpoint类型的对象，封装了log目录下的RPC文件
  // RPC文件中记录了log目录下的所有log的recoveryPoint
  private val recoveryPointCheckpoints = logDirs.map(dir => (dir, new OffsetCheckpoint(new File(dir, RecoveryPointCheckpointFile)))).toMap
  loadLogs()

  // public, so we can access this from kafka.admin.DeleteTopicTest
  val cleaner: LogCleaner =
    if(cleanerConfig.enableCleaner)
      new LogCleaner(cleanerConfig, logDirs, logs, time = time)
    else
      null
  
  /**
   * Create and check validity of the given directories, specifically:
   * <ol>
   * <li> Ensure that there are no duplicates in the directory list
   * <li> Create each directory if it doesn't exist
   * <li> Check that each path is a readable directory 
   * </ol>
   */
  private def createAndValidateLogDirs(dirs: Seq[File]) {
    if(dirs.map(_.getCanonicalPath).toSet.size < dirs.size)
      throw new KafkaException("Duplicate log directory found: " + logDirs.mkString(", "))
    for(dir <- dirs) {
      if(!dir.exists) {
        info("Log directory '" + dir.getAbsolutePath + "' not found, creating it.")
        val created = dir.mkdirs()
        if(!created)
          throw new KafkaException("Failed to create data directory " + dir.getAbsolutePath)
      }
      if(!dir.isDirectory || !dir.canRead)
        throw new KafkaException(dir.getAbsolutePath + " is not a readable log directory.")
    }
  }
  
  /**
   * Lock all the given directories
   */
  private def lockLogDirs(dirs: Seq[File]): Seq[FileLock] = {
    dirs.map { dir =>
      val lock = new FileLock(new File(dir, LockFile))
      if(!lock.tryLock())
        throw new KafkaException("Failed to acquire lock on file .lock in " + lock.file.getParentFile.getAbsolutePath + 
                               ". A Kafka instance in another process or thread is using this directory.")
      lock
    }
  }
  
  /**
   * Recover and load all logs in the given data directories
    *
    * LogManager初始化的重要步骤
    * 1、为每个log目录分配一个有ioThreads条线程的线程池，用来执行恢复操作
    * 2、检测broker上次关闭是否正常，并设置broker的状态，在broker正常关闭时，会创建一个".kafka_cleanshutdown"文件，在理就是通过此文件进行判断的
    * 3、载入每个Log的recoveryPoint
    * 4、为每个log创建一个恢复任务，交给线程池处理
    * 5、主线程阻塞等待所有的恢复任务完成
    * 6、关闭所有步骤1中创建的线程池
   */
  private def loadLogs(): Unit = {
    info("Loading logs.")

    // 保存所有log目录对应的线程池
    val threadPools = mutable.ArrayBuffer.empty[ExecutorService]
    val jobs = mutable.Map.empty[File, Seq[Future[_]]]

    for (dir <- this.logDirs) {
      // 步骤一
      val pool = Executors.newFixedThreadPool(ioThreads)
      threadPools.append(pool)

      // 步骤二
      val cleanShutdownFile = new File(dir, Log.CleanShutdownFile)

      if (cleanShutdownFile.exists) {
        debug(
          "Found clean shutdown file. " +
          "Skipping recovery for all logs in data directory: " +
          dir.getAbsolutePath)
      } else {
        // log recovery itself is being performed by `Log` class during initialization
        // 修改broker状态
        brokerState.newState(RecoveringFromUncleanShutdown)
      }

      // 步骤三：读取每个log目录下的RecoveryPointCheckpoint文件，并生成TopicAndPartition与recoveryPoint的对应关系
      var recoveryPoints = Map[TopicAndPartition, Long]()
      try {
        // 载入recoveryPoints
        recoveryPoints = this.recoveryPointCheckpoints(dir).read
      } catch {
        case e: Exception => {
          warn("Error occured while reading recovery-point-offset-checkpoint file of directory " + dir, e)
          warn("Resetting the recovery checkpoint to 0")
        }
      }

      // 遍历所有log目录的子文件，将文件过滤，只保留目录
      val jobsForDir = for {
        dirContent <- Option(dir.listFiles).toList
        logDir <- dirContent if logDir.isDirectory
      } yield {// 步骤四：为每个log文件夹创建一个Runnable任务
        CoreUtils.runnable {
          debug("Loading log '" + logDir.getName + "'")

          // 从目录名可以解析出topic名称和分区编号
          val topicPartition = Log.parseTopicPartitionName(logDir)
          // 获取log对应的config
          val config = topicConfigs.getOrElse(topicPartition.topic, defaultConfig)
          // 获取log对应的recoveryPoint
          val logRecoveryPoint = recoveryPoints.getOrElse(topicPartition, 0L)

          // 创建log对象
          val current = new Log(logDir, config, logRecoveryPoint, scheduler, time)
          // 将log添加到集合中
          val previous = this.logs.put(topicPartition, current)

          if (previous != null) {
            throw new IllegalArgumentException(
              "Duplicate log directories found: %s, %s!".format(
              current.dir.getAbsolutePath, previous.dir.getAbsolutePath))
          }
        }
      }

      // 将jobsForDir中的所有任务放到线程池中执行，并将Future形成seq，保存到jobs中
      jobs(cleanShutdownFile) = jobsForDir.map(pool.submit).toSeq
    }


    // 步骤五：等待jobs中的runnable完成
    try {
      for ((cleanShutdownFile, dirJobs) <- jobs) {
        dirJobs.foreach(_.get)
        // 删除cleanShutdownFile文件
        cleanShutdownFile.delete()
      }
    } catch {
      case e: ExecutionException => {
        error("There was an error in one of the threads during logs loading: " + e.getCause)
        throw e.getCause
      }
    } finally {
      // 步骤六：关闭全部的线程池
      threadPools.foreach(_.shutdown())
    }

    info("Logs loading complete.")
  }

  /**
   *  Start the background threads to flush logs and do log cleanup
   */
  def startup() {
    /* Schedule the cleanup task to delete old logs */
    if(scheduler != null) {
      info("Starting log cleanup with a period of %d ms.".format(retentionCheckMs))
      // 启动log retention任务
      scheduler.schedule("kafka-log-retention", 
                         cleanupLogs, 
                         delay = InitialTaskDelayMs, 
                         period = retentionCheckMs, 
                         TimeUnit.MILLISECONDS)
      info("Starting log flusher with a default period of %d ms.".format(flushCheckMs))
      // 启动log flusher任务
      scheduler.schedule("kafka-log-flusher", 
                         flushDirtyLogs, 
                         delay = InitialTaskDelayMs, 
                         period = flushCheckMs, 
                         TimeUnit.MILLISECONDS)
      // 启动recovery point checkpoint任务
      scheduler.schedule("kafka-recovery-point-checkpoint",
                         checkpointRecoveryPointOffsets,
                         delay = InitialTaskDelayMs,
                         period = flushCheckpointMs,
                         TimeUnit.MILLISECONDS)
    }
    // 根据log.cleaner.enable配置来决定是否启动LogCleaner
    if(cleanerConfig.enableCleaner)
      cleaner.startup()
  }

  /**
   * Close all the logs
   */
  def shutdown() {
    info("Shutting down.")

    val threadPools = mutable.ArrayBuffer.empty[ExecutorService]
    val jobs = mutable.Map.empty[File, Seq[Future[_]]]

    // stop the cleaner first
    if (cleaner != null) {
      CoreUtils.swallow(cleaner.shutdown())
    }

    // close logs in each dir
    for (dir <- this.logDirs) {
      debug("Flushing and closing logs at " + dir)

      val pool = Executors.newFixedThreadPool(ioThreads)
      threadPools.append(pool)

      val logsInDir = logsByDir.getOrElse(dir.toString, Map()).values

      val jobsForDir = logsInDir map { log =>
        CoreUtils.runnable {
          // flush the log to ensure latest possible recovery point
          log.flush()
          log.close()
        }
      }

      jobs(dir) = jobsForDir.map(pool.submit).toSeq
    }


    try {
      for ((dir, dirJobs) <- jobs) {
        dirJobs.foreach(_.get)

        // update the last flush point
        debug("Updating recovery points at " + dir)
        checkpointLogsInDir(dir)

        // mark that the shutdown was clean by creating marker file
        debug("Writing clean shutdown marker at " + dir)
        CoreUtils.swallow(new File(dir, Log.CleanShutdownFile).createNewFile())
      }
    } catch {
      case e: ExecutionException => {
        error("There was an error in one of the threads during LogManager shutdown: " + e.getCause)
        throw e.getCause
      }
    } finally {
      threadPools.foreach(_.shutdown())
      // regardless of whether the close succeeded, we need to unlock the data directories
      dirLocks.foreach(_.destroy())
    }

    info("Shutdown complete.")
  }


  /**
   * Truncate the partition logs to the specified offsets and checkpoint the recovery point to this offset
   *
   * @param partitionAndOffsets Partition logs that need to be truncated
   */
  def truncateTo(partitionAndOffsets: Map[TopicAndPartition, Long]) {
    for ((topicAndPartition, truncateOffset) <- partitionAndOffsets) {
      val log = logs.get(topicAndPartition)
      // If the log does not exist, skip it
      if (log != null) {
        //May need to abort and pause the cleaning of the log, and resume after truncation is done.
        val needToStopCleaner: Boolean = (truncateOffset < log.activeSegment.baseOffset)
        if (needToStopCleaner && cleaner != null)
          cleaner.abortAndPauseCleaning(topicAndPartition)
        log.truncateTo(truncateOffset)
        if (needToStopCleaner && cleaner != null) {
          cleaner.maybeTruncateCheckpoint(log.dir.getParentFile, topicAndPartition, log.activeSegment.baseOffset)
          cleaner.resumeCleaning(topicAndPartition)
        }
      }
    }
    checkpointRecoveryPointOffsets()
  }

  /**
   *  Delete all data in a partition and start the log at the new offset
   *  @param newOffset The new offset to start the log with
   */
  def truncateFullyAndStartAt(topicAndPartition: TopicAndPartition, newOffset: Long) {
    val log = logs.get(topicAndPartition)
    // If the log does not exist, skip it
    if (log != null) {
        //Abort and pause the cleaning of the log, and resume after truncation is done.
      if (cleaner != null)
        cleaner.abortAndPauseCleaning(topicAndPartition)
      log.truncateFullyAndStartAt(newOffset)
      if (cleaner != null) {
        cleaner.maybeTruncateCheckpoint(log.dir.getParentFile, topicAndPartition, log.activeSegment.baseOffset)
        cleaner.resumeCleaning(topicAndPartition)
      }
    }
    checkpointRecoveryPointOffsets()
  }

  /**
   * Write out the current recovery point for all logs to a text file in the log directory 
   * to avoid recovering the whole log on startup.
    * 定时将每个Log的recoveryPoint写入RecoveryPointCheckpoint
   */
  def checkpointRecoveryPointOffsets() {
    // 对每个log调用这个方法
    this.logDirs.foreach(checkpointLogsInDir)
  }

  /**
   * Make a checkpoint for all logs in provided directory.
   */
  private def checkpointLogsInDir(dir: File): Unit = {
    // 获取指定log目录下的TopicAndPartition信息，以及其对应的Log对象
    val recoveryPoints = this.logsByDir.get(dir.toString)
    if (recoveryPoints.isDefined) {
      // 更新指定log目录下的RecoveryPointCheckpoint文件
      this.recoveryPointCheckpoints(dir).write(recoveryPoints.get.mapValues(_.recoveryPoint))
    }
  }

  /**
   * Get the log if it exists, otherwise return None
   */
  def getLog(topicAndPartition: TopicAndPartition): Option[Log] = {
    val log = logs.get(topicAndPartition)
    if (log == null)
      None
    else
      Some(log)
  }

  /**
   * Create a log for the given topic and the given partition
   * If the log already exists, just return a copy of the existing log
   */
  // 选择创建Log的log目录时，会选择Log最少的log目录
  def createLog(topicAndPartition: TopicAndPartition, config: LogConfig): Log = {
    logCreationOrDeletionLock synchronized {
      var log = logs.get(topicAndPartition)
      
      // check if the log has already been created in another thread
      if(log != null)
        return log
      
      // if not, create it
      // 选择Log最少的log目录
      val dataDir = nextLogDir()
      val dir = new File(dataDir, topicAndPartition.topic + "-" + topicAndPartition.partition)
      // 创建Log对应的文件夹
      dir.mkdirs()
      // 创建Log对象，根据之前的分析，这里同时创建activeSegment
      log = new Log(dir, 
                    config,
                    recoveryPoint = 0L,
                    scheduler,
                    time)
      // 存入logs集合中
      logs.put(topicAndPartition, log)
      info("Created log for partition [%s,%d] in %s with properties {%s}."
           .format(topicAndPartition.topic, 
                   topicAndPartition.partition, 
                   dataDir.getAbsolutePath,
                   {import JavaConversions._; config.originals.mkString(", ")}))
      log
    }
  }

  /**
   *  Delete a log.
   */
  def deleteLog(topicAndPartition: TopicAndPartition) {
    var removedLog: Log = null
    logCreationOrDeletionLock synchronized {
      // 从Log集合中删除对应的Log对象
      removedLog = logs.remove(topicAndPartition)
    }
    if (removedLog != null) {
      //We need to wait until there is no more cleaning task on the log to be deleted before actually deleting it.
      if (cleaner != null) {
        // 停止对此Log的日志压缩操作，这里会阻塞等待压缩状态
        cleaner.abortCleaning(topicAndPartition)
        // 更新cleaner-offset-checkpoint文件
        cleaner.updateCheckpoints(removedLog.dir.getParentFile)
      }
      // 删除相关的日志文件，索引文件和目录
      removedLog.delete()
      info("Deleted log for partition [%s,%d] in %s."
           .format(topicAndPartition.topic,
                   topicAndPartition.partition,
                   removedLog.dir.getAbsolutePath))
    }
  }

  /**
   * Choose the next directory in which to create a log. Currently this is done
   * by calculating the number of partitions in each directory and then choosing the
   * data directory with the fewest partitions.
   */
  private def nextLogDir(): File = {
    // 只有一个log目录
    if(logDirs.size == 1) {
      logDirs(0)
    } else {
      // 指定了多个Log目录
      // count the number of logs in each parent directory (including 0 for empty directories
      // 计算Log目录的中log的数量
      val logCounts = allLogs.groupBy(_.dir.getParent).mapValues(_.size)
      val zeros = logDirs.map(dir => (dir.getPath, 0)).toMap
      var dirCounts = (zeros ++ logCounts).toBuffer
    
      // choose the directory with the least logs in it
      // 选择Log最少的log目录
      val leastLoaded = dirCounts.sortBy(_._2).head
      new File(leastLoaded._1)
    }
  }

  /**
   * Runs through the log removing segments older than a certain age
    * 根据存活时间判断是否要删除
   */
  private def cleanupExpiredSegments(log: Log): Int = {
    if (log.config.retentionMs < 0)
      return 0
    val startMs = time.milliseconds
    // 删除条件是LogSegment的日志文件最近一段时间（retentionMS）内没有内修改
    log.deleteOldSegments(startMs - _.lastModified > log.config.retentionMs)
  }

  /**
   *  Runs through the log removing segments until the size of the log
   *  is at least logRetentionSize bytes in size
   */
  private def cleanupSegmentsToMaintainSize(log: Log): Int = {
    if(log.config.retentionSize < 0 || log.size < log.config.retentionSize)
      return 0
    // 计算需要删除的字节数
    var diff = log.size - log.config.retentionSize
    // 判断该LogSegment是否应该被删除
    def shouldDelete(segment: LogSegment) = {
      if(diff - segment.size >= 0) {
        diff -= segment.size
        true
      } else {
        false
      }
    }
    log.deleteOldSegments(shouldDelete)
  }

  /**
   * Delete any eligible logs. Return the number of segments deleted.
   * 按照两个条件进行LogSegment清理工作
   * 一个是LogSegment的存活时间，二是整个log的大小
   */
  def cleanupLogs() {
    debug("Beginning log cleanup...")
    var total = 0
    val startMs = time.milliseconds
    for(log <- allLogs; if !log.config.compact) {
      debug("Garbage collecting '" + log.name + "'")
      total += cleanupExpiredSegments(log) + cleanupSegmentsToMaintainSize(log)
    }
    debug("Log cleanup completed. " + total + " files deleted in " +
                  (time.milliseconds - startMs) / 1000 + " seconds")
  }

  /**
   * Get all the partition logs
   */
  def allLogs(): Iterable[Log] = logs.values

  /**
   * Get a map of TopicAndPartition => Log
   */
  def logsByTopicPartition: Map[TopicAndPartition, Log] = logs.toMap

  /**
   * Map of log dir to logs by topic and partitions in that dir
   */
  private def logsByDir = {
    this.logsByTopicPartition.groupBy {
      case (_, log) => log.dir.getParent
    }
  }

  /**
   * Flush any log which has exceeded its flush interval and has unwritten messages.
    * 根据配置的时长定时对Log进行flush操作，保证数据的持久性
    * 条件 只有一个，log为刷新时长是否大于此log的flush.ms配置项指定的时长
   */
  private def flushDirtyLogs() = {
    debug("Checking for dirty logs to flush...")

    for ((topicAndPartition, log) <- logs) {
      try {
        val timeSinceLastFlush = time.milliseconds - log.lastFlushTime
        debug("Checking if flush is needed on " + topicAndPartition.topic + " flush interval  " + log.config.flushMs +
              " last flushed " + log.lastFlushTime + " time since last flush: " + timeSinceLastFlush)
        // 检测是否需要刷新
        if(timeSinceLastFlush >= log.config.flushMs)
          log.flush
      } catch {
        case e: Throwable =>
          error("Error flushing topic " + topicAndPartition.topic, e)
      }
    }
  }
}
