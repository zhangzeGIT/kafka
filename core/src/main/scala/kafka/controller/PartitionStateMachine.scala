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

import collection._
import collection.JavaConversions
import collection.mutable.Buffer
import java.util.concurrent.atomic.AtomicBoolean
import kafka.api.LeaderAndIsr
import kafka.common.{LeaderElectionNotNeededException, TopicAndPartition, StateChangeFailedException, NoReplicaOnlineException}
import kafka.utils.{Logging, ReplicationUtils}
import kafka.utils.ZkUtils._
import org.I0Itec.zkclient.{IZkDataListener, IZkChildListener}
import org.I0Itec.zkclient.exception.ZkNodeExistsException
import kafka.controller.Callbacks.CallbackBuilder
import kafka.utils.CoreUtils._

/**
 * This class represents the state machine for partitions. It defines the states that a partition can be in, and
 * transitions to move the partition to another legal state. The different states that a partition can be in are -
 * 1. NonExistentPartition: This state indicates that the partition was either never created or was created and then
 *                          deleted. Valid previous state, if one exists, is OfflinePartition
 * 2. NewPartition        : After creation, the partition is in the NewPartition state. In this state, the partition should have
 *                          replicas assigned to it, but no leader/isr yet. Valid previous states are NonExistentPartition
 * 3. OnlinePartition     : Once a leader is elected for a partition, it is in the OnlinePartition state.
 *                          Valid previous states are NewPartition/OfflinePartition
 * 4. OfflinePartition    : If, after successful leader election, the leader for partition dies, then the partition
 *                          moves to the OfflinePartition state. Valid previous states are NewPartition/OnlinePartition
 */
// 维护分区状态的状态机
//    分区状态通过PartitionState接口定义
class PartitionStateMachine(controller: KafkaController) extends Logging {
  // ControllerContext对象，维护KafkaController的上下文信息
  private val controllerContext = controller.controllerContext
  private val controllerId = controller.config.brokerId
  // ZK客户端
  private val zkUtils = controllerContext.zkUtils
  // 记录每个分区对应的PartitionState状态
  private val partitionState: mutable.Map[TopicAndPartition, PartitionState] = mutable.Map.empty
  // 向指定Broker批量发送请求
  private val brokerRequestBatch = new ControllerBrokerRequestBatch(controller)
  private val hasStarted = new AtomicBoolean(false)
  // 默认的leader副本的选举类器
  private val noOpPartitionLeaderSelector = new NoOpLeaderSelector(controllerContext)
  // ZK监听器
  private val topicChangeListener = new TopicChangeListener()
  private val deleteTopicsListener = new DeleteTopicsListener()
  // 监听分区修改
  private val partitionModificationsListeners: mutable.Map[String, PartitionModificationsListener] = mutable.Map.empty
  private val stateChangeLogger = KafkaController.stateChangeLogger

  this.logIdent = "[Partition state machine on Controller " + controllerId + "]: "

  /**
   * Invoked on successful controller election. First registers a topic change listener since that triggers all
   * state transitions for partitions. Initializes the state of partitions by reading from zookeeper. Then triggers
   * the OnlinePartition state change for all new or offline partitions.
   */
  // 启动会对集合进行初始化
  def startup() {
    // initialize partition state
    // 初始化分区的状态
    initializePartitionState()
    // set started flag
    // 标识PartitionStateMachine已启动
    hasStarted.set(true)
    // try to move partitions to online state
    // 尝试将分区切换到OnlinePartition状态(将NewPartition和OfflinePartition两种状态切换)
    triggerOnlinePartitionStateChange()

    info("Started partition state machine with initial state -> " + partitionState.toString())
  }

  // register topic and partition change listeners
  def registerListeners() {
    registerTopicChangeListener()
    if(controller.config.deleteTopicEnable)
      registerDeleteTopicListener()
  }

  // de-register topic and partition change listeners
  def deregisterListeners() {
    deregisterTopicChangeListener()
    partitionModificationsListeners.foreach {
      case (topic, listener) =>
        zkUtils.zkClient.unsubscribeDataChanges(getTopicPath(topic), listener)
    }
    partitionModificationsListeners.clear()
    if(controller.config.deleteTopicEnable)
      deregisterDeleteTopicListener()
  }

  /**
   * Invoked on controller shutdown.
   */
  def shutdown() {
    // reset started flag
    hasStarted.set(false)
    // clear partition state
    partitionState.clear()
    // de-register all ZK listeners
    deregisterListeners()

    info("Stopped partition state machine")
  }

  /**
   * This API invokes the OnlinePartition state change on all partitions in either the NewPartition or OfflinePartition
   * state. This is called on a successful controller election and on broker changes
   */
  // 将OfflinePartition和NewPartition状态的分区转换成OnlinePartition
  def triggerOnlinePartitionStateChange() {
    try {
      // 检查ControllerBrokerRequestBatch中的三个集合是否为空
      brokerRequestBatch.newBatch()
      // try to move all partitions in NewPartition or OfflinePartition state to OnlinePartition state except partitions
      // that belong to topics to be deleted
      for((topicAndPartition, partitionState) <- partitionState
          if(!controller.deleteTopicManager.isTopicQueuedUpForDeletion(topicAndPartition.topic))) {
        if(partitionState.equals(OfflinePartition) || partitionState.equals(NewPartition))
          // 对指定分区进行状态切换，切换为OnlinePartition
          handleStateChange(topicAndPartition.topic, topicAndPartition.partition, OnlinePartition, controller.offlinePartitionSelector,
                            (new CallbackBuilder).build)
      }
      // 发送请求
      brokerRequestBatch.sendRequestsToBrokers(controller.epoch)
    } catch {
      case e: Throwable => error("Error while moving some partitions to the online state", e)
      // TODO: It is not enough to bail out and log an error, it is important to trigger leader election for those partitions
    }
  }

  def partitionsInState(state: PartitionState): Set[TopicAndPartition] = {
    partitionState.filter(p => p._2 == state).keySet
  }

  /**
   * This API is invoked by the partition change zookeeper listener
   * @param partitions   The list of partitions that need to be transitioned to the target state
   * @param targetState  The state that the partitions should be moved to
   */
  // 对指定集合循环调用handleStateChange方法进行状态转换
  def handleStateChanges(partitions: Set[TopicAndPartition], targetState: PartitionState,
                         leaderSelector: PartitionLeaderSelector = noOpPartitionLeaderSelector,
                         callbacks: Callbacks = (new CallbackBuilder).build) {
    info("Invoking state change to %s for partitions %s".format(targetState, partitions.mkString(",")))
    try {
      brokerRequestBatch.newBatch()
      partitions.foreach { topicAndPartition =>
        handleStateChange(topicAndPartition.topic, topicAndPartition.partition, targetState, leaderSelector, callbacks)
      }
      brokerRequestBatch.sendRequestsToBrokers(controller.epoch)
    }catch {
      case e: Throwable => error("Error while moving some partitions to %s state".format(targetState), e)
      // TODO: It is not enough to bail out and log an error, it is important to trigger state changes for those partitions
    }
  }

  /**
   * This API exercises the partition's state machine. It ensures that every state transition happens from a legal
   * previous state to the target state. Valid state transitions are:
   * NonExistentPartition -> NewPartition:
   * --load assigned replicas from ZK to controller cache
   *
   * NewPartition -> OnlinePartition
   * --assign first live replica as the leader and all live replicas as the isr; write leader and isr to ZK for this partition
   * --send LeaderAndIsr request to every live replica and UpdateMetadata request to every live broker
   *
   * OnlinePartition,OfflinePartition -> OnlinePartition
   * --select new leader and isr for this partition and a set of replicas to receive the LeaderAndIsr request, and write leader and isr to ZK
   * --for this partition, send LeaderAndIsr request to every receiving replica and UpdateMetadata request to every live broker
   *
   * NewPartition,OnlinePartition,OfflinePartition -> OfflinePartition
   * --nothing other than marking partition state as Offline
   *
   * OfflinePartition -> NonExistentPartition
   * --nothing other than marking the partition state as NonExistentPartition
   * @param topic       The topic of the partition for which the state transition is invoked
   * @param partition   The partition for which the state transition is invoked
   * @param targetState The end state that the partition should be moved to
   */
  // 管理分区状态的核心方法
  // 第三个参数：用来选举leader副本的partitionLeaderSelector对象，也就是选举规则
  private def handleStateChange(topic: String, partition: Int, targetState: PartitionState,
                                leaderSelector: PartitionLeaderSelector,
                                callbacks: Callbacks) {
    val topicAndPartition = TopicAndPartition(topic, partition)
    // 检测当前PartitionStateMachine对象是否已经启动，只用controller leader才会启动
    if (!hasStarted.get)
      throw new StateChangeFailedException(("Controller %d epoch %d initiated state change for partition %s to %s failed because " +
                                            "the partition state machine has not started")
                                              .format(controllerId, controller.epoch, topicAndPartition, targetState))
    // 从partitionState集合中获取分区的状态，如果没有对应的状态，则初始化NonExistentPartition
    val currState = partitionState.getOrElseUpdate(topicAndPartition, NonExistentPartition)
    try {
      targetState match {
        // 将分区状态设置为NewPartition
        case NewPartition =>
          // pre: partition did not exist before this
          assertValidPreviousStates(topicAndPartition, List(NonExistentPartition), NewPartition)
          partitionState.put(topicAndPartition, NewPartition)
          val assignedReplicas = controllerContext.partitionReplicaAssignment(topicAndPartition).mkString(",")
          stateChangeLogger.trace("Controller %d epoch %d changed partition %s state from %s to %s with assigned replicas %s"
                                    .format(controllerId, controller.epoch, topicAndPartition, currState, targetState,
                                            assignedReplicas))
          // post: partition has been assigned replicas
        case OnlinePartition =>
          assertValidPreviousStates(topicAndPartition, List(NewPartition, OnlinePartition, OfflinePartition), OnlinePartition)
          partitionState(topicAndPartition) match {
            // 为分区初始化leader副本和ISR集合
            case NewPartition =>
              // initialize leader and isr path for new partition
              initializeLeaderAndIsrForPartition(topicAndPartition)
            // 为分区选举新的leader副本
            case OfflinePartition =>
              electLeaderForPartition(topic, partition, leaderSelector)
            // 为分区重新选举新的leader副本
            case OnlinePartition => // invoked when the leader needs to be re-elected
              electLeaderForPartition(topic, partition, leaderSelector)
            case _ => // should never come here since illegal previous states are checked above
          }
          partitionState.put(topicAndPartition, OnlinePartition)
          val leader = controllerContext.partitionLeadershipInfo(topicAndPartition).leaderAndIsr.leader
          stateChangeLogger.trace("Controller %d epoch %d changed partition %s from %s to %s with leader %d"
                                    .format(controllerId, controller.epoch, topicAndPartition, currState, targetState, leader))
           // post: partition has a leader
        case OfflinePartition =>
          // pre: partition should be in New or Online state
          assertValidPreviousStates(topicAndPartition, List(NewPartition, OnlinePartition, OfflinePartition), OfflinePartition)
          // should be called when the leader for a partition is no longer alive
          stateChangeLogger.trace("Controller %d epoch %d changed partition %s state from %s to %s"
                                    .format(controllerId, controller.epoch, topicAndPartition, currState, targetState))
          partitionState.put(topicAndPartition, OfflinePartition)
          // post: partition has no alive leader
        case NonExistentPartition =>
          // pre: partition should be in Offline state
          assertValidPreviousStates(topicAndPartition, List(OfflinePartition), NonExistentPartition)
          stateChangeLogger.trace("Controller %d epoch %d changed partition %s state from %s to %s"
                                    .format(controllerId, controller.epoch, topicAndPartition, currState, targetState))
          partitionState.put(topicAndPartition, NonExistentPartition)
          // post: partition state is deleted from all brokers and zookeeper
      }
    } catch {
      case t: Throwable =>
        stateChangeLogger.error("Controller %d epoch %d initiated state change for partition %s from %s to %s failed"
          .format(controllerId, controller.epoch, topicAndPartition, currState, targetState), t)
    }
  }

  /**
   * Invoked on startup of the partition's state machine to set the initial state for all existing partitions in
   * zookeeper
   */
  // 每个分区初始化状态的依据是controllerContext.partitionLeadershipInfo中记录的leader副本信息和ISR集合信息
  private def initializePartitionState() {
    // 遍历所有分区
    for((topicPartition, replicaAssignment) <- controllerContext.partitionReplicaAssignment) {
      // check if leader and isr path exists for partition. If not, then it is in NEW state
      controllerContext.partitionLeadershipInfo.get(topicPartition) match {
        // 存在leader副本和ISR集合信息
        case Some(currentLeaderIsrAndEpoch) =>
          // else, check if the leader for partition is alive. If yes, it is in Online state, else it is in Offline state
          controllerContext.liveBrokerIds.contains(currentLeaderIsrAndEpoch.leaderAndIsr.leader) match {
            // leader副本所在的broker可用，初始化为OnlinePartition状态
            case true => // leader is alive
              partitionState.put(topicPartition, OnlinePartition)
            // leader副本所在的broker不可用，初始化为OfflineParition状态
            case false =>
              partitionState.put(topicPartition, OfflinePartition)
          }
        // 没有leader副本和ISR集合的信息
        case None =>
          partitionState.put(topicPartition, NewPartition)
      }
    }
  }

  private def assertValidPreviousStates(topicAndPartition: TopicAndPartition, fromStates: Seq[PartitionState],
                                        targetState: PartitionState) {
    if(!fromStates.contains(partitionState(topicAndPartition)))
      throw new IllegalStateException("Partition %s should be in the %s states before moving to %s state"
        .format(topicAndPartition, fromStates.mkString(","), targetState) + ". Instead it is in %s state"
        .format(partitionState(topicAndPartition)))
  }

  /**
   * Invoked on the NewPartition->OnlinePartition state change. When a partition is in the New state, it does not have
   * a leader and isr path in zookeeper. Once the partition moves to the OnlinePartition state, its leader and isr
   * path gets initialized and it never goes back to the NewPartition state. From here, it can only go to the
   * OfflinePartition state.
   * @param topicAndPartition   The topic/partition whose leader and isr path is to be initialized
   */
  // 1、从controllerContext.partitionReplicaAssignment集合中选择第一个可用的副本作为leader副本，其余的构成ISR集合
  // 2、将leader副本和ISR集合的信息写入到ZK
  // 3、更新ControllerContext.partitionLeadershipInfo中缓存的leader副本，ISR集合等信息
  // 4、将上述确定的leader副本，ISR集合，AR集合等信息天际到ControllerBrokerRequestBatch
  //     封装LeaderAndIsrRequest发送给相关的Broker
  private def initializeLeaderAndIsrForPartition(topicAndPartition: TopicAndPartition) {
    // 获取分区AR集合
    val replicaAssignment = controllerContext.partitionReplicaAssignment(topicAndPartition)
    // 获取AR集合中可用副本集合
    val liveAssignedReplicas = replicaAssignment.filter(r => controllerContext.liveBrokerIds.contains(r))
    liveAssignedReplicas.size match {
      // 没有任何副本，跑出异常
      case 0 =>
        val failMsg = ("encountered error during state change of partition %s from New to Online, assigned replicas are [%s], " +
                       "live brokers are [%s]. No assigned replica is alive.")
                         .format(topicAndPartition, replicaAssignment.mkString(","), controllerContext.liveBrokerIds)
        stateChangeLogger.error("Controller %d epoch %d ".format(controllerId, controller.epoch) + failMsg)
        throw new StateChangeFailedException(failMsg)
      case _ =>
        debug("Live assigned replicas for partition %s are: [%s]".format(topicAndPartition, liveAssignedReplicas))
        // make the first replica in the list of assigned replicas, the leader
        // 将可用的AR集合中的第一个副本选为leader
        val leader = liveAssignedReplicas.head
        // 创建对象，其中ISR集合是可用AR集合，leaderEpoch和zkVersion初始化为零
        val leaderIsrAndControllerEpoch = new LeaderIsrAndControllerEpoch(new LeaderAndIsr(leader, liveAssignedReplicas.toList),
          controller.epoch)
        debug("Initializing leader and isr for partition %s to %s".format(topicAndPartition, leaderIsrAndControllerEpoch))
        try {
          zkUtils.createPersistentPath(
            // 将LeaderAndIsrAndControllerEpoch中的信息转化成Json格式保存到zk
            getTopicPartitionLeaderAndIsrPath(topicAndPartition.topic, topicAndPartition.partition),
            zkUtils.leaderAndIsrZkData(leaderIsrAndControllerEpoch.leaderAndIsr, controller.epoch))
          // NOTE: the above write can fail only if the current controller lost its zk session and the new controller
          // took over and initialized this partition. This can happen if the current controller went into a long
          // GC pause
          // 更新partitionLeadershipInfo中的记录
          controllerContext.partitionLeadershipInfo.put(topicAndPartition, leaderIsrAndControllerEpoch)
          // 添加LeaderAndIsrRequest，待发送
          brokerRequestBatch.addLeaderAndIsrRequestForBrokers(liveAssignedReplicas, topicAndPartition.topic,
            topicAndPartition.partition, leaderIsrAndControllerEpoch, replicaAssignment)
        } catch {
          case e: ZkNodeExistsException =>
            // read the controller epoch
            val leaderIsrAndEpoch = ReplicationUtils.getLeaderIsrAndEpochForPartition(zkUtils, topicAndPartition.topic,
              topicAndPartition.partition).get
            val failMsg = ("encountered error while changing partition %s's state from New to Online since LeaderAndIsr path already " +
                           "exists with value %s and controller epoch %d")
                             .format(topicAndPartition, leaderIsrAndEpoch.leaderAndIsr.toString(), leaderIsrAndEpoch.controllerEpoch)
            stateChangeLogger.error("Controller %d epoch %d ".format(controllerId, controller.epoch) + failMsg)
            throw new StateChangeFailedException(failMsg)
        }
    }
  }

  /**
   * Invoked on the OfflinePartition,OnlinePartition->OnlinePartition state change.
   * It invokes the leader election API to elect a leader for the input offline partition
   * @param topic               The topic of the offline partition
   * @param partition           The offline partition
   * @param leaderSelector      Specific leader selector (e.g., offline/reassigned/etc.)
   */
  // 1.使用指定的PartitionLeaderSelector未分区选举新的Leader副本
  // 2、将leader副本和ISR集合的信息写入到ZK
  // 3、更新ControllerContext.paritionLeadershipInfo集合中缓存的leader副本，ISR集合等信息
  // 4、将上述步骤中确定的leader副本，isr集合，AR集合添加到ControllerBrokerRequestBatch
  //    之后封装成LeaderAndIsrRequest发送给相关的broker
  def electLeaderForPartition(topic: String, partition: Int, leaderSelector: PartitionLeaderSelector) {
    val topicAndPartition = TopicAndPartition(topic, partition)
    // handle leader election for the partitions whose leader is no longer alive
    stateChangeLogger.trace("Controller %d epoch %d started leader election for partition %s"
                              .format(controllerId, controller.epoch, topicAndPartition))
    try {
      var zookeeperPathUpdateSucceeded: Boolean = false
      var newLeaderAndIsr: LeaderAndIsr = null
      var replicasForThisPartition: Seq[Int] = Seq.empty[Int]
      while(!zookeeperPathUpdateSucceeded) {
        // 从ZK中获取分区当前的leader副本，ISR集合，ZKVersion等信息，如果不存在，则抛异常
        val currentLeaderIsrAndEpoch = getLeaderIsrAndEpochOrThrowException(topic, partition)
        val currentLeaderAndIsr = currentLeaderIsrAndEpoch.leaderAndIsr
        val controllerEpoch = currentLeaderIsrAndEpoch.controllerEpoch
        if (controllerEpoch > controller.epoch) {
          val failMsg = ("aborted leader election for partition [%s,%d] since the LeaderAndIsr path was " +
                         "already written by another controller. This probably means that the current controller %d went through " +
                         "a soft failure and another controller was elected with epoch %d.")
                           .format(topic, partition, controllerId, controllerEpoch)
          stateChangeLogger.error("Controller %d epoch %d ".format(controllerId, controller.epoch) + failMsg)
          throw new StateChangeFailedException(failMsg)
        }
        // elect new leader or throw exception
        // 使用指定的PartitionLeaderSelector以及当前的LeaderAndIsr信息，选举新的leader副本和ISR集合
        val (leaderAndIsr, replicas) = leaderSelector.selectLeader(topicAndPartition, currentLeaderAndIsr)
        // 将新的LeaderAndIsr信息转换成JSON格式保存到ZK中
        val (updateSucceeded, newVersion) = ReplicationUtils.updateLeaderAndIsr(zkUtils, topic, partition,
          leaderAndIsr, controller.epoch, currentLeaderAndIsr.zkVersion)
        newLeaderAndIsr = leaderAndIsr
        newLeaderAndIsr.zkVersion = newVersion
        zookeeperPathUpdateSucceeded = updateSucceeded
        replicasForThisPartition = replicas
      }
      val newLeaderIsrAndControllerEpoch = new LeaderIsrAndControllerEpoch(newLeaderAndIsr, controller.epoch)
      // update the leader cache
      // 更新partitionLeadershipInfo中的记录
      controllerContext.partitionLeadershipInfo.put(TopicAndPartition(topic, partition), newLeaderIsrAndControllerEpoch)
      stateChangeLogger.trace("Controller %d epoch %d elected leader %d for Offline partition %s"
                                .format(controllerId, controller.epoch, newLeaderAndIsr.leader, topicAndPartition))
      val replicas = controllerContext.partitionReplicaAssignment(TopicAndPartition(topic, partition))
      // store new leader and isr info in cache
      // 添加LeaderAndISRRequest，待发送
      brokerRequestBatch.addLeaderAndIsrRequestForBrokers(replicasForThisPartition, topic, partition,
        newLeaderIsrAndControllerEpoch, replicas)
    } catch {
      case lenne: LeaderElectionNotNeededException => // swallow
      case nroe: NoReplicaOnlineException => throw nroe
      case sce: Throwable =>
        val failMsg = "encountered error while electing leader for partition %s due to: %s.".format(topicAndPartition, sce.getMessage)
        stateChangeLogger.error("Controller %d epoch %d ".format(controllerId, controller.epoch) + failMsg)
        throw new StateChangeFailedException(failMsg, sce)
    }
    debug("After leader election, leader cache is updated to %s".format(controllerContext.partitionLeadershipInfo.map(l => (l._1, l._2))))
  }

  private def registerTopicChangeListener() = {
    zkUtils.zkClient.subscribeChildChanges(BrokerTopicsPath, topicChangeListener)
  }

  private def deregisterTopicChangeListener() = {
    zkUtils.zkClient.unsubscribeChildChanges(BrokerTopicsPath, topicChangeListener)
  }

  def registerPartitionChangeListener(topic: String) = {
    partitionModificationsListeners.put(topic, new PartitionModificationsListener(topic))
    zkUtils.zkClient.subscribeDataChanges(getTopicPath(topic), partitionModificationsListeners(topic))
  }

  def deregisterPartitionChangeListener(topic: String) = {
    zkUtils.zkClient.unsubscribeDataChanges(getTopicPath(topic), partitionModificationsListeners(topic))
    partitionModificationsListeners.remove(topic)
  }

  private def registerDeleteTopicListener() = {
    zkUtils.zkClient.subscribeChildChanges(DeleteTopicsPath, deleteTopicsListener)
  }

  private def deregisterDeleteTopicListener() = {
    zkUtils.zkClient.unsubscribeChildChanges(DeleteTopicsPath, deleteTopicsListener)
  }

  private def getLeaderIsrAndEpochOrThrowException(topic: String, partition: Int): LeaderIsrAndControllerEpoch = {
    val topicAndPartition = TopicAndPartition(topic, partition)
    ReplicationUtils.getLeaderIsrAndEpochForPartition(zkUtils, topic, partition) match {
      case Some(currentLeaderIsrAndEpoch) => currentLeaderIsrAndEpoch
      case None =>
        val failMsg = "LeaderAndIsr information doesn't exist for partition %s in %s state"
                        .format(topicAndPartition, partitionState(topicAndPartition))
        throw new StateChangeFailedException(failMsg)
    }
  }

  /**
   * This is the zookeeper listener that triggers all the state transitions for a partition
   */
  // IZkChildListener：监听子节点发生变化
  // 负责Topic的增删，他监听/brokers/topics节点的子节点
  //
  // 举例说明：
  //          假设有三个broker
  //          管理员通过kafka-topics脚本添加一个名为test的topic
  //          它有三个分区，每个分区三个副本
  //          kafka-topics脚本向zk的/brokers/topics/test节点写入的信息为
  //              "partitions":{"0":[0,1,2],"1":[1,2,0],"2":[2,1,0]}
  //          此时触发TopicChangeListener
  //          将test的每个partition的AR集合加载到ControllerContext中
  //          在进行第一个分区状态转换（NoExistentPartition->NewPartition）
  //          和第一个副本状态切换（NoExistentReplica->NewReplica）只做状态切换
  //          第二次分区状态转换时会选取leader副本和ISR集合信息
  //          结果如表：
  //                    分区： 0    1    2
  //              leader副本： 0    1    2
  //                 ISR集合：(0,1,2) (1,2,0) (2,1,0)
  //          之后会将结果写入zk，向所有可用broker发送LeaderAndIsrRequest请求来指导副本的角色切换
  //          然后向所有可用broker发送UpdateMetadataRequest来跟新其MetadataCache
  //          第二次副本状态切换时，副本已经在AR集合中，所以并未做任何操作
  class TopicChangeListener extends IZkChildListener with Logging {
    this.logIdent = "[TopicChangeListener on Controller " + controller.config.brokerId + "]: "

    @throws(classOf[Exception])
    def handleChildChange(parentPath : String, children : java.util.List[String]) {
      inLock(controllerContext.controllerLock) {
        if (hasStarted.get) {
          try {
            val currentChildren = {
              // 获取/brokers/topics下的子节点集合
              import JavaConversions._
              debug("Topic change listener fired for path %s with children %s".format(parentPath, children.mkString(",")))
              (children: Buffer[String]).toSet
            }
            // 过滤，得到新添加topic
            val newTopics = currentChildren -- controllerContext.allTopics
            // 过滤，得到删除的topic
            val deletedTopics = controllerContext.allTopics -- currentChildren
            // 更新ControllerContext的allTopics集合
            controllerContext.allTopics = currentChildren

            // 从ZK的/brokers/topics/topic_name路径下加载每个Partition的AR集合
            val addedPartitionReplicaAssignment = zkUtils.getReplicaAssignmentForTopics(newTopics.toSeq)

            // 更新ControllerContext中AR集合记录
            controllerContext.partitionReplicaAssignment = controllerContext.partitionReplicaAssignment.filter(p =>
              !deletedTopics.contains(p._1.topic))
            controllerContext.partitionReplicaAssignment.++=(addedPartitionReplicaAssignment)
            info("New topics: [%s], deleted topics: [%s], new partition replica assignment [%s]".format(newTopics,
              deletedTopics, addedPartitionReplicaAssignment))
            if(newTopics.size > 0)
              // 处理新增topic
              controller.onNewTopicCreation(newTopics, addedPartitionReplicaAssignment.keySet.toSet)
          } catch {
            case e: Throwable => error("Error while handling new topic", e )
          }
        }
      }
    }
  }

  /**
   * Delete topics includes the following operations -
   * 1. Add the topic to be deleted to the delete topics cache, only if the topic exists
   * 2. If there are topics to be deleted, it signals the delete topic thread
   */
  // 监听/admin/delete_topics节点下的子节点变化
  // TopicCommand在该路径下添加需要被删除的Topic时，listener会被触发
  // 它会将该待删除的topic交由TopicDeletionManager执行topic删除操作
  class DeleteTopicsListener() extends IZkChildListener with Logging {
    this.logIdent = "[DeleteTopicsListener on " + controller.config.brokerId + "]: "
    val zkUtils = controllerContext.zkUtils

    /**
     * Invoked when a topic is being deleted
     * @throws Exception On any error.
     */
    @throws(classOf[Exception])
    def handleChildChange(parentPath : String, children : java.util.List[String]) {
      inLock(controllerContext.controllerLock) {
        // 从ZK中获取待删除的Topic集合
        var topicsToBeDeleted = {
          import JavaConversions._
          (children: Buffer[String]).toSet
        }
        debug("Delete topics listener fired for topics %s to be deleted".format(topicsToBeDeleted.mkString(",")))
        val nonExistentTopics = topicsToBeDeleted.filter(
          // 检查待删除的topic是否存在
          t => !controllerContext.allTopics.contains(t))
        if(nonExistentTopics.size > 0) {
          warn("Ignoring request to delete non-existing topics " + nonExistentTopics.mkString(","))
          // 对于不存在的topic，直接将其在/admin/delete_topics下对应的节点删除
          nonExistentTopics.foreach(topic => zkUtils.deletePathRecursive(getDeleteTopicPath(topic)))
        }
        // 过滤掉不存在的待删除topic
        topicsToBeDeleted --= nonExistentTopics
        if(topicsToBeDeleted.size > 0) {
          info("Starting topic deletion for topics " + topicsToBeDeleted.mkString(","))
          // mark topic ineligible for deletion if other state changes are in progress
          // 检测待删除topic是否处于不可删除的情况
          topicsToBeDeleted.foreach { topic =>
            // 检测topic中是否有分区正在进行优先副本选举
            val preferredReplicaElectionInProgress =
              controllerContext.partitionsUndergoingPreferredReplicaElection.map(_.topic).contains(topic)
            // 检测Topic中是否有分区在进行副本重新分配
            val partitionReassignmentInProgress =
              controllerContext.partitionsBeingReassigned.keySet.map(_.topic).contains(topic)
            if(preferredReplicaElectionInProgress || partitionReassignmentInProgress)
              // 将topic标记为不可删除
              controller.deleteTopicManager.markTopicIneligibleForDeletion(Set(topic))
          }
          // add topic to deletion list
          // 将可删除的topic提交给TopicDeletionManager执行删除操作
          controller.deleteTopicManager.enqueueTopicsForDeletion(topicsToBeDeleted)
        }
      }
    }

    /**
     *
     * @throws Exception
   *             On any error.
     */
    @throws(classOf[Exception])
    def handleDataDeleted(dataPath: String) {
    }
  }

  // 新增的每个Topic都会注册一个这个listener
  // 删除topic也会把这个listener删除
  // 会监听/brokers/topics/[topic_name]节点中的数据变化
  // 主要用于监听一个topic的分区变化
  class PartitionModificationsListener(topic: String) extends IZkDataListener with Logging {

    this.logIdent = "[AddPartitionsListener on " + controller.config.brokerId + "]: "

    @throws(classOf[Exception])
    def handleDataChange(dataPath : String, data: Object) {
      inLock(controllerContext.controllerLock) {
        try {
          info(s"Partition modification triggered $data for path $dataPath")
          // 从zk中获取Topic的partition记录
          val partitionReplicaAssignment = zkUtils.getReplicaAssignmentForTopics(List(topic))
          // 过滤出新增分区记录
          val partitionsToBeAdded = partitionReplicaAssignment.filter(p =>
            !controllerContext.partitionReplicaAssignment.contains(p._1))
          // 如果topic正在进行删除，则输出错误日志返回
          if(controller.deleteTopicManager.isTopicQueuedUpForDeletion(topic))
            error("Skipping adding partitions %s for topic %s since it is currently being deleted"
                  .format(partitionsToBeAdded.map(_._1.partition).mkString(","), topic))
          else {
            if (partitionsToBeAdded.size > 0) {
              info("New partitions to be added %s".format(partitionsToBeAdded))
              // 将新增分区信息添加到ControllerContext中
              controllerContext.partitionReplicaAssignment.++=(partitionsToBeAdded)
              // 切换新增分区及其副本的状态，最终使其上线对外提供服务
              controller.onNewPartitionCreation(partitionsToBeAdded.keySet.toSet)
            }
          }
        } catch {
          case e: Throwable => error("Error while handling add partitions for data path " + dataPath, e )
        }
      }
    }

    @throws(classOf[Exception])
    def handleDataDeleted(parentPath : String) {
      // this is not implemented for partition change
    }
  }
}
// 3->0：从zk中加载分区的AR集合到ControllerContext的partitionReplicaAssignment集合中
// 0->1：首先将leader副本和ISR集合的信息写入到ZK中，这里会将分区的AR集合中第一个可用的副本选为leader
//       ①之后向所有可用副本发送LeaderAndIsrRequest，指导这些副本进行L/F的角色切换
//       ②并且向所有broker发送UpdateMetadataRequest来更新其上的MetadataCache
// 1/2->1：为分区选择新的leader副本和ISR集合，并将结果写入zk，之后进行①②
// 0/1->2：只进行状态转换，并没有其他的操作
// 2->3：只进行状态转换，并没有其他操作
sealed trait PartitionState { def state: Byte }
// 分区创建后就处于此状态，此时分区可能已经被分配了AR集合，但是还没有指定leader副本和ISR集合
case object NewPartition extends PartitionState { val state: Byte = 0 }
// 分区成功选举出leader副本之后
case object OnlinePartition extends PartitionState { val state: Byte = 1 }
// 已经成功选举出分区的leader副本后，但leader副本发生宕机、或者新建的分区直接转换为此状态
case object OfflinePartition extends PartitionState { val state: Byte = 2 }
// 分区从来没有被创建或者分区创建之后又被删除了
case object NonExistentPartition extends PartitionState { val state: Byte = 3 }
