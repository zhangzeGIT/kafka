/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.common

import java.util.concurrent.atomic.AtomicBoolean

import kafka.utils.{Time, SystemTime, ZkUtils, Logging}
import org.apache.zookeeper.Watcher.Event.KeeperState
import org.I0Itec.zkclient.exception.ZkInterruptedException
import org.I0Itec.zkclient.{IZkStateListener, IZkChildListener}
import scala.collection.JavaConverters._

/**
 * Handle the notificationMessage.
 */
trait NotificationHandler {
  def processNotification(notificationMessage: String)
}

/**
 * A listener that subscribes to seqNodeRoot for any child changes where all children are assumed to be sequence node
 * with seqNodePrefix. When a child is added under seqNodeRoot this class gets notified, it looks at lastExecutedChange
 * number to avoid duplicate processing and if it finds an unprocessed child, it reads its data and calls supplied
 * notificationHandler's processNotification() method with the child's data as argument. As part of processing these changes it also
 * purges any children with currentTime - createTime > changeExpirationMs.
 *
 * The caller/user of this class should ensure that they use zkClient.subscribeStateChanges and call processAllNotifications
 * method of this class from ZkStateChangeListener's handleNewSession() method. This is necessary to ensure that if zk session
 * is terminated and reestablished any missed notification will be processed immediately.
 */
// seqNodeRoot：指定监听的路径，这里的值是/kafka-acl-changes
// seqNodePrefix：持久顺序节点的前缀，这里的值是acl_changes_
// notificationHandler：seqNodeRoot路径下子节点集合发生变化时，执行响应操作
// changeExpirationMs：顺序节点被创建后，超过changeExpirationMs指定的时间，则认为可以被删除，默认15分钟
// lastExecutedChange：记录上次处理的顺序节点的编号
class ZkNodeChangeNotificationListener(private val zkUtils: ZkUtils,
                                       private val seqNodeRoot: String,
                                       private val seqNodePrefix: String,
                                       private val notificationHandler: NotificationHandler,
                                       private val changeExpirationMs: Long = 15 * 60 * 1000,
                                       private val time: Time = SystemTime) extends Logging {
  private var lastExecutedChange = -1L
  private val isClosed = new AtomicBoolean(false)

  /**
   * create seqNodeRoot and begin watching for any new children nodes.
   * 完成注册监听器的操作
   */
  def init() {
    // 确保kafka-acl-changes节点存在
    zkUtils.makeSurePersistentPathExists(seqNodeRoot)
    // 注册NodeChangeListener，监听节点
    zkUtils.zkClient.subscribeChildChanges(seqNodeRoot, NodeChangeListener)
    // 监听ZK的链接状态变化
    zkUtils.zkClient.subscribeStateChanges(ZkStateChangeListener)
    // 处理kafka-acl-changes的子节点
    processAllNotifications()
  }

  def close() = {
    isClosed.set(true)
  }

  /**
   * Process all changes
   */
  // NodeChangeListener和ZkStateChangeListener被触发时，都会调用这个方法处理kafka-acl-changes的子节点
  def processAllNotifications() {
    val changes = zkUtils.zkClient.getChildren(seqNodeRoot)
    processNotifications(changes.asScala.sorted)
  }

  /**
   * Process the given list of notifications
   */
  private def processNotifications(notifications: Seq[String]) {
    if (notifications.nonEmpty) {
      info(s"Processing notification(s) to $seqNodeRoot")
      try {
        val now = time.milliseconds
        // 遍历子节点集合
        for (notification <- notifications) {
          // 获取子节点编号
          val changeId = changeNumber(notification)
          // 检测此子节点是否已经处理过
          if (changeId > lastExecutedChange) {
            val changeZnode = seqNodeRoot + "/" + notification
            // 读取节点状态信息和其中记录的数据
            val (data, stat) = zkUtils.readDataMaybeNull(changeZnode)
            // 调用这个方法，更新aclCache集合
            data map (notificationHandler.processNotification(_)) getOrElse (logger.warn(s"read null data from $changeZnode when processing notification $notification"))
          }
          lastExecutedChange = changeId
        }
        // 删除过期节点
        purgeObsoleteNotifications(now, notifications)
      } catch {
        case e: ZkInterruptedException =>
          if (!isClosed.get)
            throw e
      }
    }
  }

  /**
   * Purges expired notifications.
   * @param now
   * @param notifications
   */
  private def purgeObsoleteNotifications(now: Long, notifications: Seq[String]) {
    for (notification <- notifications.sorted) {
      val notificationNode = seqNodeRoot + "/" + notification
      // 读取节点状态信息和其中记录的数据
      val (data, stat) = zkUtils.readDataMaybeNull(notificationNode)
      if (data.isDefined) {
        // 检测节点是否过期
        if (now - stat.getCtime > changeExpirationMs) {
          debug(s"Purging change notification $notificationNode")
          // 删除节点
          zkUtils.deletePath(notificationNode)
        }
      }
    }
  }

  /* get the change number from a change notification znode */
  private def changeNumber(name: String): Long = name.substring(seqNodePrefix.length).toLong

  /**
   * A listener that gets invoked when a node is created to notify changes.
   */
  object NodeChangeListener extends IZkChildListener {
    override def handleChildChange(path: String, notifications: java.util.List[String]) {
      try {
        import scala.collection.JavaConverters._
        if (notifications != null)
          processNotifications(notifications.asScala.sorted)
      } catch {
        case e: Exception => error(s"Error processing notification change for path = $path and notification= $notifications :", e)
      }
    }
  }

  object ZkStateChangeListener extends IZkStateListener {

    override def handleNewSession() {
      processAllNotifications
    }

    override def handleSessionEstablishmentError(error: Throwable) {
      fatal("Could not establish session with zookeeper", error)
    }

    override def handleStateChanged(state: KeeperState) {
      debug(s"New zookeeper state: ${state}")
    }
  }

}

