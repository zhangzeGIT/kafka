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
package kafka.controller

import java.net.SocketTimeoutException
import java.util.concurrent.{BlockingQueue, LinkedBlockingQueue}

import kafka.api._
import kafka.cluster.Broker
import kafka.common.{KafkaException, TopicAndPartition}
import kafka.server.KafkaConfig
import kafka.utils._
import org.apache.kafka.clients.{ClientRequest, ClientResponse, ManualMetadataUpdater, NetworkClient}
import org.apache.kafka.common.metrics.Metrics
import org.apache.kafka.common.network.{ChannelBuilders, LoginType, Mode, NetworkReceive, Selectable, Selector}
import org.apache.kafka.common.protocol.{ApiKeys, SecurityProtocol}
import org.apache.kafka.common.requests
import org.apache.kafka.common.requests.{UpdateMetadataRequest, _}
import org.apache.kafka.common.utils.Time
import org.apache.kafka.common.{Node, TopicPartition}

import scala.collection.JavaConverters._
import scala.collection.{Set, mutable}
import scala.collection.mutable.HashMap

class ControllerChannelManager(controllerContext: ControllerContext, config: KafkaConfig, time: Time, metrics: Metrics, threadNamePrefix: Option[String] = None) extends Logging {
  // 表示与一个Broker连接的各种信息
  protected val brokerStateInfo = new HashMap[Int, ControllerBrokerStateInfo]
  private val brokerLock = new Object
  this.logIdent = "[Channel manager on controller " + config.brokerId + "]: "

  // 为broker创建对应的ControllerBrokerStateInfo对象
  controllerContext.liveBrokers.foreach(addNewBroker(_))

  def startup() = {
    brokerLock synchronized {
      // 启动RequestSendThread线程
      brokerStateInfo.foreach(brokerState => startRequestSendThread(brokerState._1))
    }
  }

  def shutdown() = {
    brokerLock synchronized {
      brokerStateInfo.values.foreach(removeExistingBroker)
    }
  }

  def sendRequest(brokerId: Int, apiKey: ApiKeys, apiVersion: Option[Short], request: AbstractRequest, callback: AbstractRequestResponse => Unit = null) {
    brokerLock synchronized {
      val stateInfoOpt = brokerStateInfo.get(brokerId)
      stateInfoOpt match {
        case Some(stateInfo) =>
          stateInfo.messageQueue.put(QueueItem(apiKey, apiVersion, request, callback))
        case None =>
          warn("Not sending request %s to broker %d, since it is offline.".format(request, brokerId))
      }
    }
  }

  def addBroker(broker: Broker) {
    // be careful here. Maybe the startup() API has already started the request send thread
    brokerLock synchronized {
      if(!brokerStateInfo.contains(broker.id)) {
        addNewBroker(broker)
        startRequestSendThread(broker.id)
      }
    }
  }

  def removeBroker(brokerId: Int) {
    brokerLock synchronized {
      removeExistingBroker(brokerStateInfo(brokerId))
    }
  }

  // 对brokerStateInfo集合的管理
  private def addNewBroker(broker: Broker) {
    // 创建消息队列
    val messageQueue = new LinkedBlockingQueue[QueueItem]
    debug("Controller %d trying to connect to broker %d".format(config.brokerId, broker.id))
    val brokerEndPoint = broker.getBrokerEndPoint(config.interBrokerSecurityProtocol)
    val brokerNode = new Node(broker.id, brokerEndPoint.host, brokerEndPoint.port)
    // 创建NetworkClient对象
    val networkClient = {
      val channelBuilder = ChannelBuilders.create(
        config.interBrokerSecurityProtocol,
        Mode.CLIENT,
        LoginType.SERVER,
        config.values,
        config.saslMechanismInterBrokerProtocol,
        config.saslInterBrokerHandshakeRequestEnable
      )
      // 创建Selector对象，第三个参数是metrics，KafkaServer启动时创建
      val selector = new Selector(
        NetworkReceive.UNLIMITED,
        config.connectionsMaxIdleMs,
        metrics,
        time,
        "controller-channel",
        Map("broker-id" -> broker.id.toString).asJava,
        false,
        channelBuilder
      )
      new NetworkClient(
        selector,
        new ManualMetadataUpdater(Seq(brokerNode).asJava),
        config.brokerId.toString,
        1,
        0,
        Selectable.USE_DEFAULT_BUFFER_SIZE,
        Selectable.USE_DEFAULT_BUFFER_SIZE,
        config.requestTimeoutMs,
        time
      )
    }
    val threadName = threadNamePrefix match {
      case None => "Controller-%d-to-broker-%d-send-thread".format(config.brokerId, broker.id)
      case Some(name) => "%s:Controller-%d-to-broker-%d-send-thread".format(name, config.brokerId, broker.id)
    }

    // 创建一个RequestSendThread线程
    val requestThread = new RequestSendThread(config.brokerId, controllerContext, messageQueue, networkClient,
      brokerNode, config, time, threadName)
    requestThread.setDaemon(false)
    // 填充brokerStateInfo集合
    brokerStateInfo.put(broker.id, new ControllerBrokerStateInfo(networkClient, brokerNode, messageQueue, requestThread))
  }

  // 对brokerStateInfo集合的管理
  private def removeExistingBroker(brokerState: ControllerBrokerStateInfo) {
    try {
      // 关闭底层链接
      brokerState.networkClient.close()
      // 清空队列
      brokerState.messageQueue.clear()
      // 关闭RequestSendThread
      brokerState.requestSendThread.shutdown()
      // 清除ControllerBrokerStateInfo对象
      brokerStateInfo.remove(brokerState.brokerNode.id)
    } catch {
      case e: Throwable => error("Error while removing broker by the controller", e)
    }
  }

  protected def startRequestSendThread(brokerId: Int) {
    val requestThread = brokerStateInfo(brokerId).requestSendThread
    if(requestThread.getState == Thread.State.NEW)
      requestThread.start()
  }
}

case class QueueItem(apiKey: ApiKeys, apiVersion: Option[Short], request: AbstractRequest, callback: AbstractRequestResponse => Unit)

// 继承ShutdownableThread，在线程停止之前会循环执行doWork
//    通过NetworkClientBlockingOps完成发送请求并阻塞等待响应
class RequestSendThread(val controllerId: Int,
                        val controllerContext: ControllerContext,
                        val queue: BlockingQueue[QueueItem],
                        val networkClient: NetworkClient,
                        val brokerNode: Node,
                        val config: KafkaConfig,
                        val time: Time,
                        name: String)
  extends ShutdownableThread(name = name) {

  private val lock = new Object()
  private val stateChangeLogger = KafkaController.stateChangeLogger
  private val socketTimeoutMs = config.controllerSocketTimeoutMs

  override def doWork(): Unit = {

    def backoff(): Unit = CoreUtils.swallowTrace(Thread.sleep(300))

    // 获取缓冲队列的QueueItem
    val QueueItem(apiKey, apiVersion, request, callback) = queue.take()
    import NetworkClientBlockingOps._
    var clientResponse: ClientResponse = null
    try {
      lock synchronized {
        var isSendSuccessful = false
        while (isRunning.get() && !isSendSuccessful) {
          // if a broker goes down for a long time, then at some point the controller's zookeeper listener will trigger a
          // removeBroker which will invoke shutdown() on this thread. At that point, we will stop retrying.
          // 当broker宕机后，会触发zk的监听器调用removeBroker方法将当前的线程停止，在停止前会一直尝试重试
          try {
            // 阻塞等待符合发送条件
            if (!brokerReady()) {
              isSendSuccessful = false
              // 退避一段时间，三秒
              backoff()
            }
            else {
              val requestHeader = apiVersion.fold(networkClient.nextRequestHeader(apiKey))(networkClient.nextRequestHeader(apiKey, _))
              val send = new RequestSend(brokerNode.idString, requestHeader, request.toStruct)
              // 创建ClientRequest对象
              val clientRequest = new ClientRequest(time.milliseconds(), true, send, null)
              // 发送请求并阻塞等待响应
              clientResponse = networkClient.blockingSendAndReceive(clientRequest)(time)
              // 设置发送成功的标记
              isSendSuccessful = true
            }
          } catch {
            case e: Throwable => // if the send was not successful, reconnect to broker and resend the message
              warn(("Controller %d epoch %d fails to send request %s to broker %s. " +
                "Reconnecting to broker.").format(controllerId, controllerContext.epoch,
                  request.toString, brokerNode.toString()), e)
              networkClient.close(brokerNode.idString)
              isSendSuccessful = false
              backoff()
          }
        }
        if (clientResponse != null) {
          // 检测请求类型，controller只能发送LeaderAndIsrRequest,StopReplicaRequest和UpdateMetadataRequest三种请求
          val response = ApiKeys.forId(clientResponse.request.request.header.apiKey) match {
            case ApiKeys.LEADER_AND_ISR => new LeaderAndIsrResponse(clientResponse.responseBody)
            case ApiKeys.STOP_REPLICA => new StopReplicaResponse(clientResponse.responseBody)
            case ApiKeys.UPDATE_METADATA_KEY => new UpdateMetadataResponse(clientResponse.responseBody)
            case apiKey => throw new KafkaException(s"Unexpected apiKey received: $apiKey")
          }
          stateChangeLogger.trace("Controller %d epoch %d received response %s for a request sent to broker %s"
            .format(controllerId, controllerContext.epoch, response.toString, brokerNode.toString))

          // 调用QueueItem中封装的回调函数
          if (callback != null) {
            callback(response)
          }
        }
      }
    } catch {
      case e: Throwable =>
        error("Controller %d fails to send a request to broker %s".format(controllerId, brokerNode.toString()), e)
        // If there is any socket error (eg, socket timeout), the connection is no longer usable and needs to be recreated.
        networkClient.close(brokerNode.idString)
    }
  }

  private def brokerReady(): Boolean = {
    import NetworkClientBlockingOps._
    try {

      if (networkClient.isReady(brokerNode, time.milliseconds()))
        true
      else {
        val ready = networkClient.blockingReady(brokerNode, socketTimeoutMs)(time)

        if (!ready)
          throw new SocketTimeoutException(s"Failed to connect within $socketTimeoutMs ms")

        info("Controller %d connected to %s for sending state change requests".format(controllerId, brokerNode.toString()))
        true
      }
    } catch {
      case e: Throwable =>
        warn("Controller %d's connection to broker %s was unsuccessful".format(controllerId, brokerNode.toString()), e)
        networkClient.close(brokerNode.idString)
        false
    }
  }

}

// 批量发送controller leader与集群中其他broker的请求
class ControllerBrokerRequestBatch(controller: KafkaController) extends  Logging {
  val controllerContext = controller.controllerContext
  val controllerId: Int = controller.config.brokerId
  // 记录了发往指定broker的leaderAndIsrRequest所需的信息
  val leaderAndIsrRequestMap = mutable.Map.empty[Int, mutable.Map[TopicPartition, PartitionStateInfo]]
  // 记录了发往指定broker的StopReplicaRequest所需的消息
  val stopReplicaRequestMap = mutable.Map.empty[Int, Seq[StopReplicaRequestInfo]]
  // 记录了发往指定brokerUpdateMetadataRequest集合
  val updateMetadataRequestMap = mutable.Map.empty[Int, mutable.Map[TopicPartition, PartitionStateInfo]]
  private val stateChangeLogger = KafkaController.stateChangeLogger

  // 检查三个请求集合是否为空，如果不为空则抛异常
  def newBatch() {
    // raise error if the previous batch is not empty
    if (leaderAndIsrRequestMap.size > 0)
      throw new IllegalStateException("Controller to broker state change requests batch is not empty while creating " +
        "a new one. Some LeaderAndIsr state changes %s might be lost ".format(leaderAndIsrRequestMap.toString()))
    if (stopReplicaRequestMap.size > 0)
      throw new IllegalStateException("Controller to broker state change requests batch is not empty while creating a " +
        "new one. Some StopReplica state changes %s might be lost ".format(stopReplicaRequestMap.toString()))
    if (updateMetadataRequestMap.size > 0)
      throw new IllegalStateException("Controller to broker state change requests batch is not empty while creating a " +
        "new one. Some UpdateMetadata state changes %s might be lost ".format(updateMetadataRequestMap.toString()))
  }

  def clear() {
    leaderAndIsrRequestMap.clear()
    stopReplicaRequestMap.clear()
    updateMetadataRequestMap.clear()
  }

  // 向leaderAndIsrRequestMap集合中添加待发送的LeaderAndISRRequest所需的数据
  // // 同时会调用addUpdateMetadataRequestForBrokers方法准备向集群中所有可用的broker发送UpdateMetadataRequest
  //    第一个参数：接收LeaderAndIsrRequest的broker集合
  def addLeaderAndIsrRequestForBrokers(brokerIds: Seq[Int], topic: String, partition: Int,
                                       leaderIsrAndControllerEpoch: LeaderIsrAndControllerEpoch,
                                       replicas: Seq[Int], callback: AbstractRequestResponse => Unit = null) {
    val topicPartition = new TopicPartition(topic, partition)

    // 查找broker对应的集合
    brokerIds.filter(_ >= 0).foreach { brokerId =>
      val result = leaderAndIsrRequestMap.getOrElseUpdate(brokerId, mutable.Map.empty)
      // 添加Leader，ISR，AR等构造LeaderAndIsrRequest请求需要的信息
      result.put(topicPartition, PartitionStateInfo(leaderIsrAndControllerEpoch, replicas.toSet))
    }

    // 准备向所有的broker发送UpdateMetadataRequest
    addUpdateMetadataRequestForBrokers(controllerContext.liveOrShuttingDownBrokerIds.toSeq,
                                       Set(TopicAndPartition(topic, partition)))
  }

  def addStopReplicaRequestForBrokers(brokerIds: Seq[Int], topic: String, partition: Int, deletePartition: Boolean,
                                      callback: (AbstractRequestResponse, Int) => Unit = null) {
    brokerIds.filter(b => b >= 0).foreach { brokerId =>
      stopReplicaRequestMap.getOrElseUpdate(brokerId, Seq.empty[StopReplicaRequestInfo])
      val v = stopReplicaRequestMap(brokerId)
      if(callback != null)
        stopReplicaRequestMap(brokerId) = v :+ StopReplicaRequestInfo(PartitionAndReplica(topic, partition, brokerId),
          deletePartition, (r: AbstractRequestResponse) => callback(r, brokerId))
      else
        stopReplicaRequestMap(brokerId) = v :+ StopReplicaRequestInfo(PartitionAndReplica(topic, partition, brokerId),
          deletePartition)
    }
  }

  /** Send UpdateMetadataRequest to the given brokers for the given partitions and partitions that are being deleted */
  def addUpdateMetadataRequestForBrokers(brokerIds: Seq[Int],
                                         partitions: collection.Set[TopicAndPartition] = Set.empty[TopicAndPartition],
                                         callback: AbstractRequestResponse => Unit = null) {
    // 定义回调函数
    def updateMetadataRequestMapFor(partition: TopicAndPartition, beingDeleted: Boolean) {
      // 找出controller中保存的该分区的leader
      val leaderIsrAndControllerEpochOpt = controllerContext.partitionLeadershipInfo.get(partition)
      leaderIsrAndControllerEpochOpt match {
        case Some(leaderIsrAndControllerEpoch) =>
          // 获取分区的AR集合
          val replicas = controllerContext.partitionReplicaAssignment(partition).toSet
          val partitionStateInfo = if (beingDeleted) {
            // 根据begingDeleted参数设置leader的值
            val leaderAndIsr = new LeaderAndIsr(LeaderAndIsr.LeaderDuringDelete, leaderIsrAndControllerEpoch.leaderAndIsr.isr)
            PartitionStateInfo(LeaderIsrAndControllerEpoch(leaderAndIsr, leaderIsrAndControllerEpoch.controllerEpoch), replicas)
          } else {
            PartitionStateInfo(leaderIsrAndControllerEpoch, replicas)
          }
          // 向updateMetadataRequestMap中添加数据
          brokerIds.filter(b => b >= 0).foreach { brokerId =>
            updateMetadataRequestMap.getOrElseUpdate(brokerId, mutable.Map.empty[TopicPartition, PartitionStateInfo])
            updateMetadataRequestMap(brokerId).put(new TopicPartition(partition.topic, partition.partition), partitionStateInfo)
          }
        case None =>
          info("Leader not yet assigned for partition %s. Skip sending UpdateMetadataRequest.".format(partition))
      }
    }

    // 如果指定分区集合为空，则需要更新全部分区
    val filteredPartitions = {
      val givenPartitions = if (partitions.isEmpty)
        controllerContext.partitionLeadershipInfo.keySet
      else
        partitions
      // 过滤即将删除的partition
      if (controller.deleteTopicManager.partitionsToBeDeleted.isEmpty)
        givenPartitions
      else
        givenPartitions -- controller.deleteTopicManager.partitionsToBeDeleted
    }
    if (filteredPartitions.isEmpty)
      brokerIds.filter(b => b >= 0).foreach { brokerId =>
        updateMetadataRequestMap.getOrElseUpdate(brokerId, mutable.Map.empty[TopicPartition, PartitionStateInfo])
      }
    else
    // 将filteredPartitions中的分区信息添加到updateMetadataRequestMap集合中，等待发送
      filteredPartitions.foreach(partition => updateMetadataRequestMapFor(partition, beingDeleted = false))

    // 将即将删除的分区信息添加到updateMetadataRequestMap集合中，等待发送
    // 注意beingDeleted参数
    controller.deleteTopicManager.partitionsToBeDeleted.foreach(partition => updateMetadataRequestMapFor(partition, beingDeleted = true))
  }

  // 会将三个集合中的数据来创建响应的请求，并添加到ControllerChannelManager中对应的messageQueue队列中
  // 最终由RequestSendThread线程将请求发送出去
  def sendRequestsToBrokers(controllerEpoch: Int) {
    try {
      // 处理leaderAndIsrRequestMap集合
      leaderAndIsrRequestMap.foreach { case (broker, partitionStateInfos) =>
        partitionStateInfos.foreach { case (topicPartition, state) =>
          val typeOfRequest = if (broker == state.leaderIsrAndControllerEpoch.leaderAndIsr.leader) "become-leader" else "become-follower"
          stateChangeLogger.trace(("Controller %d epoch %d sending %s LeaderAndIsr request %s to broker %d " +
                                   "for partition [%s,%d]").format(controllerId, controllerEpoch, typeOfRequest,
                                                                   state.leaderIsrAndControllerEpoch, broker,
                                                                   topicPartition.topic, topicPartition.partition))
        }
        val leaderIds = partitionStateInfos.map(_._2.leaderIsrAndControllerEpoch.leaderAndIsr.leader).toSet
        val leaders = controllerContext.liveOrShuttingDownBrokers.filter(b => leaderIds.contains(b.id)).map {
          _.getNode(controller.config.interBrokerSecurityProtocol)
        }
        val partitionStates = partitionStateInfos.map { case (topicPartition, partitionStateInfo) =>
          val LeaderIsrAndControllerEpoch(leaderIsr, controllerEpoch) = partitionStateInfo.leaderIsrAndControllerEpoch
          val partitionState = new requests.PartitionState(controllerEpoch, leaderIsr.leader,
            leaderIsr.leaderEpoch, leaderIsr.isr.map(Integer.valueOf).asJava, leaderIsr.zkVersion,
            partitionStateInfo.allReplicas.map(Integer.valueOf).asJava
          )
          topicPartition -> partitionState
        }
        // 根据集合创建LeaderAndIsrRequest对象
        val leaderAndIsrRequest = new LeaderAndIsrRequest(controllerId, controllerEpoch, partitionStates.asJava, leaders.asJava)
        controller.sendRequest(broker, ApiKeys.LEADER_AND_ISR, None, leaderAndIsrRequest, null)
      }
      leaderAndIsrRequestMap.clear()
      // 处理updateMetadataRequestMap集合
      updateMetadataRequestMap.foreach { case (broker, partitionStateInfos) =>

        partitionStateInfos.foreach(p => stateChangeLogger.trace(("Controller %d epoch %d sending UpdateMetadata request %s " +
          "to broker %d for partition %s").format(controllerId, controllerEpoch, p._2.leaderIsrAndControllerEpoch,
          broker, p._1)))
        val partitionStates = partitionStateInfos.map { case (topicPartition, partitionStateInfo) =>
          val LeaderIsrAndControllerEpoch(leaderIsr, controllerEpoch) = partitionStateInfo.leaderIsrAndControllerEpoch
          val partitionState = new requests.PartitionState(controllerEpoch, leaderIsr.leader,
            leaderIsr.leaderEpoch, leaderIsr.isr.map(Integer.valueOf).asJava, leaderIsr.zkVersion,
            partitionStateInfo.allReplicas.map(Integer.valueOf).asJava
          )
          topicPartition -> partitionState
        }

        val version = if (controller.config.interBrokerProtocolVersion >= KAFKA_0_10_0_IV1) 2: Short
                      else if (controller.config.interBrokerProtocolVersion >= KAFKA_0_9_0) 1: Short
                      else 0: Short

        val updateMetadataRequest =
          if (version == 0) {
            val liveBrokers = controllerContext.liveOrShuttingDownBrokers.map(_.getNode(SecurityProtocol.PLAINTEXT))
            new UpdateMetadataRequest(controllerId, controllerEpoch, liveBrokers.asJava, partitionStates.asJava)
          }
          else {
            val liveBrokers = controllerContext.liveOrShuttingDownBrokers.map { broker =>
              val endPoints = broker.endPoints.map { case (securityProtocol, endPoint) =>
                securityProtocol -> new UpdateMetadataRequest.EndPoint(endPoint.host, endPoint.port)
              }
              new UpdateMetadataRequest.Broker(broker.id, endPoints.asJava, broker.rack.orNull)
            }
            new UpdateMetadataRequest(version, controllerId, controllerEpoch, partitionStates.asJava, liveBrokers.asJava)
          }

        controller.sendRequest(broker, ApiKeys.UPDATE_METADATA_KEY, Some(version), updateMetadataRequest, null)
      }
      updateMetadataRequestMap.clear()

      // 处理stopReplicaRequestMap集合
      stopReplicaRequestMap.foreach { case (broker, replicaInfoList) =>
        val stopReplicaWithDelete = replicaInfoList.filter(_.deletePartition).map(_.replica).toSet
        val stopReplicaWithoutDelete = replicaInfoList.filterNot(_.deletePartition).map(_.replica).toSet
        debug("The stop replica request (delete = true) sent to broker %d is %s"
          .format(broker, stopReplicaWithDelete.mkString(",")))
        debug("The stop replica request (delete = false) sent to broker %d is %s"
          .format(broker, stopReplicaWithoutDelete.mkString(",")))
        replicaInfoList.foreach { r =>
          val stopReplicaRequest = new StopReplicaRequest(controllerId, controllerEpoch, r.deletePartition,
            Set(new TopicPartition(r.replica.topic, r.replica.partition)).asJava)
          controller.sendRequest(broker, ApiKeys.STOP_REPLICA, None, stopReplicaRequest, r.callback)
        }
      }
      stopReplicaRequestMap.clear()
    } catch {
      case e : Throwable => {
        if (leaderAndIsrRequestMap.size > 0) {
          error("Haven't been able to send leader and isr requests, current state of " +
              s"the map is $leaderAndIsrRequestMap. Exception message: $e")
        }
        if (updateMetadataRequestMap.size > 0) {
          error("Haven't been able to send metadata update requests, current state of " +
              s"the map is $updateMetadataRequestMap. Exception message: $e")
        }
        if (stopReplicaRequestMap.size > 0) {
          error("Haven't been able to send stop replica requests, current state of " +
              s"the map is $stopReplicaRequestMap. Exception message: $e")
        }
        throw new IllegalStateException(e)
      }
    }
  }
}

// networkClient与NetworkClientBlockingOps配合实现阻塞的使用方式
// brokerNode维护了对应的Broker的网络位置信息，其中记录了broker的host，IP，port以及机架信息
// requestSendThread：用于发送请求的线程
// messageQueue：缓冲队列，存放发往对应broker的请求
case class ControllerBrokerStateInfo(networkClient: NetworkClient,
                                     brokerNode: Node,
                                     messageQueue: BlockingQueue[QueueItem],
                                     requestSendThread: RequestSendThread)

case class StopReplicaRequestInfo(replica: PartitionAndReplica, deletePartition: Boolean, callback: AbstractRequestResponse => Unit = null)

class Callbacks private (var leaderAndIsrResponseCallback: AbstractRequestResponse => Unit = null,
                         var updateMetadataResponseCallback: AbstractRequestResponse => Unit = null,
                         var stopReplicaResponseCallback: (AbstractRequestResponse, Int) => Unit = null)

object Callbacks {
  class CallbackBuilder {
    var leaderAndIsrResponseCbk: AbstractRequestResponse => Unit = null
    var updateMetadataResponseCbk: AbstractRequestResponse => Unit = null
    var stopReplicaResponseCbk: (AbstractRequestResponse, Int) => Unit = null

    def leaderAndIsrCallback(cbk: AbstractRequestResponse => Unit): CallbackBuilder = {
      leaderAndIsrResponseCbk = cbk
      this
    }

    def updateMetadataCallback(cbk: AbstractRequestResponse => Unit): CallbackBuilder = {
      updateMetadataResponseCbk = cbk
      this
    }

    def stopReplicaCallback(cbk: (AbstractRequestResponse, Int) => Unit): CallbackBuilder = {
      stopReplicaResponseCbk = cbk
      this
    }

    def build: Callbacks = {
      new Callbacks(leaderAndIsrResponseCbk, updateMetadataResponseCbk, stopReplicaResponseCbk)
    }
  }
}
