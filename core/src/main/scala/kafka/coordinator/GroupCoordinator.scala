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
package kafka.coordinator

import java.util.Properties
import java.util.concurrent.atomic.AtomicBoolean

import kafka.common.{OffsetAndMetadata, OffsetMetadataAndError, TopicAndPartition}
import kafka.log.LogConfig
import kafka.message.ProducerCompressionCodec
import kafka.server._
import kafka.utils._
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.utils.Time
import org.apache.kafka.common.protocol.Errors
import org.apache.kafka.common.requests.{OffsetFetchResponse, JoinGroupRequest}

import scala.collection.{Map, Seq, immutable}

case class GroupConfig(groupMinSessionTimeoutMs: Int,
                       groupMaxSessionTimeoutMs: Int)

case class JoinGroupResult(members: Map[String, Array[Byte]],
                           memberId: String,
                           generationId: Int,
                           subProtocol: String,
                           leaderId: String,
                           errorCode: Short)

/**
 * GroupCoordinator handles general group membership and offset management.
 *
 * Each Kafka server instantiates a coordinator which is responsible for a set of
 * groups. Groups are assigned to coordinators based on their group names.
 */
// groupConfig：记录了Consumer Group中Consumer Session过期的最小时长和最大时长
// offsetConfig：记录了OffsetMetadata相关的配置项，例如metadata字段允许的最长长度，Offsets Topic中每个分区的副本个数等
// groupManager：GroupMetadataManager对象
// heartbeatPurgatory：管理DelayedHeartbeat
// joinPurgatory：管理DelayedJoin
class GroupCoordinator(val brokerId: Int,
                       val groupConfig: GroupConfig,
                       val offsetConfig: OffsetConfig,
                       val groupManager: GroupMetadataManager,
                       val heartbeatPurgatory: DelayedOperationPurgatory[DelayedHeartbeat],
                       val joinPurgatory: DelayedOperationPurgatory[DelayedJoin],
                       time: Time) extends Logging {
  type JoinCallback = JoinGroupResult => Unit
  type SyncCallback = (Array[Byte], Short) => Unit

  this.logIdent = "[GroupCoordinator " + brokerId + "]: "

  private val isActive = new AtomicBoolean(false)

  def offsetsTopicConfigs: Properties = {
    val props = new Properties
    props.put(LogConfig.CleanupPolicyProp, LogConfig.Compact)
    props.put(LogConfig.SegmentBytesProp, offsetConfig.offsetsTopicSegmentBytes.toString)
    props.put(LogConfig.CompressionTypeProp, ProducerCompressionCodec.name)
    props
  }

  /**
   * NOTE: If a group lock and metadataLock are simultaneously needed,
   * be sure to acquire the group lock before metadataLock to prevent deadlock
   */

  /**
   * Startup logic executed at the same time when the server starts up.
   */
  def startup() {
    info("Starting up.")
    isActive.set(true)
    info("Startup complete.")
  }

  /**
   * Shutdown logic executed at the same time when server shuts down.
   * Ordering of actions should be reversed from the startup process.
   */
  def shutdown() {
    info("Shutting down.")
    isActive.set(false)
    groupManager.shutdown()
    heartbeatPurgatory.shutdown()
    joinPurgatory.shutdown()
    info("Shutdown complete.")
  }

  def handleJoinGroup(groupId: String,
                      memberId: String,
                      clientId: String,
                      clientHost: String,
                      sessionTimeoutMs: Int,
                      protocolType: String,
                      protocols: List[(String, Array[Byte])],
                      responseCallback: JoinCallback) {
    if (!isActive.get) {// 检测GroupCoordinator是否启动
      responseCallback(joinError(memberId, Errors.GROUP_COORDINATOR_NOT_AVAILABLE.code))
    } else if (!validGroupId(groupId)) {// 检测GroupID是否合法
      responseCallback(joinError(memberId, Errors.INVALID_GROUP_ID.code))
    } else if (!isCoordinatorForGroup(groupId)) {// 检测GroupCoordinator是否管理此Consumer Group
      responseCallback(joinError(memberId, Errors.NOT_COORDINATOR_FOR_GROUP.code))
    } else if (isCoordinatorLoadingInProgress(groupId)) {// GroupCoordinator是否已经加载此Consumer Group对应的Offsets Topic分区
      responseCallback(joinError(memberId, Errors.GROUP_LOAD_IN_PROGRESS.code))
    } else if (sessionTimeoutMs < groupConfig.groupMinSessionTimeoutMs ||
               sessionTimeoutMs > groupConfig.groupMaxSessionTimeoutMs) {
      // 检测Consumer指定超时时长是否在合法区间
      responseCallback(joinError(memberId, Errors.INVALID_SESSION_TIMEOUT.code))
    } else {
      // only try to create the group if the group is not unknown AND
      // the member id is UNKNOWN, if member is specified but group does not
      // exist we should reject the request
      var group = groupManager.getGroup(groupId)
      if (group == null) {
        // 检测memberId是否是合法的
        if (memberId != JoinGroupRequest.UNKNOWN_MEMBER_ID) {
          // 返回错误码
          responseCallback(joinError(memberId, Errors.UNKNOWN_MEMBER_ID.code))
        } else {
          // 创建GroupMetadata对象
          group = groupManager.addGroup(new GroupMetadata(groupId, protocolType))
          // 调用doJoinGroup方法完成后续功能
          doJoinGroup(group, memberId, clientId, clientHost, sessionTimeoutMs, protocolType, protocols, responseCallback)
        }
      } else {
        doJoinGroup(group, memberId, clientId, clientHost, sessionTimeoutMs, protocolType, protocols, responseCallback)
      }
    }
  }

  // 检测：
  //    1、检测Member支持的PartitionAssignor
  //    2、检测memberId，joinGroupRequest可能来自ConsumerGroup中已知的Member
  /**
    * 按照ConsumerGroup的状态分类处理：
    *   1、Dead:直接返回UNKNOWN_MEMBER_ID错误码
    *   2、PreparingRebalance：
    *     a)如果是已知Member重新申请加入，则更新GroupMetadata中记录的Member信息
    *     b)未知Member申请加入，创建Member并分配memberId，并加入GroupMetadata中
    *   3、AwaitingSync：
    *     a)未知的新member申请加入，创建并分配memberId，并加入GroupMetadata中，然后调用maybePrepareRebalance切换状态
    *     b)已知，看Member支持的PartitionAssignor是否发生变化
    *       若变化，则将当前Member集合信息返回给GroupLeader，更新Member信息，并调用maybePrepareRebalance切换状态
    *   4、Stable：
    *     a)未知的新Member申请加入，创建member并分配memberId，并加入GroupMetadata中，然后调用maybePrepareRebalance方法切换状态
    *     b)已知，看分区Member支持的PartitionAssignor是否发生了变化
    *       如果发生变化或者发送JoinGroupRequest请求的是GroupLeader，则更新Member信息，并调用maybe……
    *       如果未发生变化，则将GroupMetadata的当前状态放回，消费者会发送SyncGroupRequest继续后面的操作
    *   5、根据当前的状态决定是否尝试完成相关的DelayedJoin操作
    */
  private def doJoinGroup(group: GroupMetadata,
                          memberId: String,
                          clientId: String,
                          clientHost: String,
                          sessionTimeoutMs: Int,
                          protocolType: String,
                          protocols: List[(String, Array[Byte])],
                          responseCallback: JoinCallback) {
    group synchronized {
      // 检测Member支持的PartitionAssignor
      if (group.protocolType != protocolType || !group.supportsProtocols(protocols.map(_._1).toSet)) {
        // if the new member does not support the group protocol, reject it
        // 返回错误码
        responseCallback(joinError(memberId, Errors.INCONSISTENT_GROUP_PROTOCOL.code))
        // 检测memberId是否能被识别
      } else if (memberId != JoinGroupRequest.UNKNOWN_MEMBER_ID && !group.has(memberId)) {
        // if the member trying to register with a un-recognized id, send the response to let
        // it reset its member id and retry
        // 返回错误码
        responseCallback(joinError(memberId, Errors.UNKNOWN_MEMBER_ID.code))
      } else {
        // 根据Consumer Group的状态分类进行处理
        group.currentState match {
          // 返回错误码
          case Dead =>
            // if the group is marked as dead, it means some other thread has just removed the group
            // from the coordinator metadata; this is likely that the group has migrated to some other
            // coordinator OR the group is in a transient unstable phase. Let the member retry
            // joining without the specified member id,
            responseCallback(joinError(memberId, Errors.UNKNOWN_MEMBER_ID.code))

          case PreparingRebalance =>
            // 判断是否为已知Member，未知Member申请加入
            if (memberId == JoinGroupRequest.UNKNOWN_MEMBER_ID) {
              addMemberAndRebalance(sessionTimeoutMs, clientId, clientHost, protocols, group, responseCallback)
            } else {// 已知Member重新申请加入
              val member = group.get(memberId)
              updateMemberAndRebalance(group, member, protocols, responseCallback)
            }

          case AwaitingSync =>
            if (memberId == JoinGroupRequest.UNKNOWN_MEMBER_ID) {
              // 未知Member申请加入会发生状态切换
              addMemberAndRebalance(sessionTimeoutMs, clientId, clientHost, protocols, group, responseCallback)
            } else {
              // 已知Member重新申请加入
              val member = group.get(memberId)
              if (member.matches(protocols)) {
                // member is joining with the same metadata (which could be because it failed to
                // receive the initial JoinGroup response), so just return current group information
                // for the current generation.
                // 支持的PartitionAssignor未发生改变，返回GroupMetadata的信息
                responseCallback(JoinGroupResult(
                  members = if (memberId == group.leaderId) {
                    group.currentMemberMetadata
                  } else {
                    Map.empty
                  },
                  memberId = memberId,
                  generationId = group.generationId,
                  subProtocol = group.protocol,
                  leaderId = group.leaderId,
                  errorCode = Errors.NONE.code))
              } else {
                // member has changed metadata, so force a rebalance
                // 支持的PartitionAssignor发生改变，需要更新Member信息并发生状态切换
                updateMemberAndRebalance(group, member, protocols, responseCallback)
              }
            }

          case Stable =>
            if (memberId == JoinGroupRequest.UNKNOWN_MEMBER_ID) {
              // if the member id is unknown, register the member to the group
              // 未知Member申请加入会发生状态切换
              addMemberAndRebalance(sessionTimeoutMs, clientId, clientHost, protocols, group, responseCallback)
            } else {
              // 已知Member重新申请加入
              val member = group.get(memberId)
              if (memberId == group.leaderId || !member.matches(protocols)) {
                // force a rebalance if a member has changed metadata or if the leader sends JoinGroup.
                // The latter allows the leader to trigger rebalances for changes affecting assignment
                // which do not affect the member metadata (such as topic metadata changes for the consumer)
                // Group Leader或支持PartitionAssignor发生改变
                // 更新Member信息并发生状态切换
                updateMemberAndRebalance(group, member, protocols, responseCallback)
              } else {
                // for followers with no actual change to their metadata, just return group information
                // for the current generation which will allow them to issue SyncGroup
                // 支持的PartitionAssignor未发生改变，返回GroupMetadata信息
                responseCallback(JoinGroupResult(
                  members = Map.empty,
                  memberId = memberId,
                  generationId = group.generationId,
                  subProtocol = group.protocol,
                  leaderId = group.leaderId,
                  errorCode = Errors.NONE.code))
              }
            }
        }

        // 尝试完成相关的DelayedJoin
        if (group.is(PreparingRebalance))
          joinPurgatory.checkAndComplete(GroupKey(group.groupId))
      }
    }
  }

  def handleSyncGroup(groupId: String,
                      generation: Int,
                      memberId: String,
                      groupAssignment: Map[String, Array[Byte]],
                      responseCallback: SyncCallback) {
    if (!isActive.get) {
      responseCallback(Array.empty, Errors.GROUP_COORDINATOR_NOT_AVAILABLE.code)
    } else if (!isCoordinatorForGroup(groupId)) {
      responseCallback(Array.empty, Errors.NOT_COORDINATOR_FOR_GROUP.code)
    } else {
      val group = groupManager.getGroup(groupId)
      if (group == null)
        responseCallback(Array.empty, Errors.UNKNOWN_MEMBER_ID.code)
      else
        doSyncGroup(group, generation, memberId, groupAssignment, responseCallback)
    }
  }

  private def doSyncGroup(group: GroupMetadata,
                          generationId: Int,
                          memberId: String,
                          groupAssignment: Map[String, Array[Byte]],
                          responseCallback: SyncCallback) {
    var delayedGroupStore: Option[DelayedStore] = None

    group synchronized {
      // 检测memberID
      if (!group.has(memberId)) {
        responseCallback(Array.empty, Errors.UNKNOWN_MEMBER_ID.code)
      } else if (generationId != group.generationId) {// 检测generationId
        responseCallback(Array.empty, Errors.ILLEGAL_GENERATION.code)
      } else {
        group.currentState match {
          // 直接返回错误码
          case Dead =>
            responseCallback(Array.empty, Errors.UNKNOWN_MEMBER_ID.code)
          // 直接返回错误码
          case PreparingRebalance =>
            responseCallback(Array.empty, Errors.REBALANCE_IN_PROGRESS.code)

          case AwaitingSync =>
            // 设置awaitingSyncCallback回调函数
            group.get(memberId).awaitingSyncCallback = responseCallback
            // 更新最后一次收到心跳的时间戳，并创建新的DelayedHeartbeat对象等待下次心跳到来
            completeAndScheduleNextHeartbeatExpiration(group, group.get(memberId))

            // if this is the leader, then we can attempt to persist state and transition to stable
            // 处理GroupLeader发送的SyncGroupRequest
            if (memberId == group.leaderId) {
              info(s"Assignment received from leader for group ${group.groupId} for generation ${group.generationId}")

              // fill any missing members with an empty assignment
              // 将未分配到分区的Member对应的分配结果填充为空的Byte数组
              val missing = group.allMembers -- groupAssignment.keySet
              val assignment = groupAssignment ++ missing.map(_ -> Array.empty[Byte]).toMap

              // 通过GroupMetadataManager将GroupMetadata相关信息形成消息，并写入对应的OffsetsTopic分区中
              delayedGroupStore = Some(groupManager.prepareStoreGroup(group, assignment, (errorCode: Short) => {
                group synchronized {
                  // another member may have joined the group while we were awaiting this callback,
                  // so we must ensure we are still in the AwaitingSync state and the same generation
                  // when it gets invoked. if we have transitioned to another state, then do nothing
                  // 首先，检测Consumer Group状态以及年代信息
                  if (group.is(AwaitingSync) && generationId == group.generationId) {
                    if (errorCode != Errors.NONE.code) {
                      // 清空分区的分配结果，发送异常响应
                      resetAndPropagateAssignmentError(group, errorCode)
                      // 切换成PreparingRebalance状态
                      maybePrepareRebalance(group)
                    } else {
                      // 重点分析此方法的内容（设置分区的分配结果，发送正常的SyncGroupResponse）
                      setAndPropagateAssignment(group, assignment)
                      // Consumer Group的状态
                      group.transitionTo(Stable)
                    }
                  }
                }
              }))
            }

          case Stable =>
            // if the group is stable, we just return the current assignment
            // 将分配给此Member的负责处理的分区信息返回
            val memberMetadata = group.get(memberId)
            responseCallback(memberMetadata.assignment, Errors.NONE.code)
            // 心跳相关操作
            completeAndScheduleNextHeartbeatExpiration(group, group.get(memberId))
        }
      }
    }

    // store the group metadata without holding the group lock to avoid the potential
    // for deadlock when the callback is invoked
    delayedGroupStore.foreach(groupManager.store)
  }

  def handleLeaveGroup(groupId: String, consumerId: String, responseCallback: Short => Unit) {
    if (!isActive.get) {
      responseCallback(Errors.GROUP_COORDINATOR_NOT_AVAILABLE.code)
    } else if (!isCoordinatorForGroup(groupId)) {
      responseCallback(Errors.NOT_COORDINATOR_FOR_GROUP.code)
    } else if (isCoordinatorLoadingInProgress(groupId)) {
      responseCallback(Errors.GROUP_LOAD_IN_PROGRESS.code)
    } else {
      val group = groupManager.getGroup(groupId)
      if (group == null) {
        // if the group is marked as dead, it means some other thread has just removed the group
        // from the coordinator metadata; this is likely that the group has migrated to some other
        // coordinator OR the group is in a transient unstable phase. Let the consumer to retry
        // joining without specified consumer id,
        responseCallback(Errors.UNKNOWN_MEMBER_ID.code)
      } else {
        group synchronized {
          if (group.is(Dead)) {
            responseCallback(Errors.UNKNOWN_MEMBER_ID.code)
          } else if (!group.has(consumerId)) {
            responseCallback(Errors.UNKNOWN_MEMBER_ID.code)
          } else {
            val member = group.get(consumerId)
            removeHeartbeatForLeavingMember(group, member)
            onMemberFailure(group, member)
            responseCallback(Errors.NONE.code)
          }
        }
      }
    }
  }

  /**
    * 首先进行一系列的检测，保证GroupMetadataManager处于可用状态并且对应ConsumerGroup的管理者
    * 之后检测ConsumerGroup状态，MemberId，generationId是否合法
    * 最后，调用complete……方法
    */
  def handleHeartbeat(groupId: String,
                      memberId: String,
                      generationId: Int,
                      responseCallback: Short => Unit) {
    if (!isActive.get) {
      responseCallback(Errors.GROUP_COORDINATOR_NOT_AVAILABLE.code)
    } else if (!isCoordinatorForGroup(groupId)) {// 检测GroupCoordinator是否管理该Consumer Group
      responseCallback(Errors.NOT_COORDINATOR_FOR_GROUP.code)
    } else if (isCoordinatorLoadingInProgress(groupId)) {// 是否已经加载对应的OffsetsTopic分区
      // the group is still loading, so respond just blindly
      responseCallback(Errors.NONE.code)
    } else {
      val group = groupManager.getGroup(groupId)
      if (group == null) {// GroupMetadata是否存在
        responseCallback(Errors.UNKNOWN_MEMBER_ID.code)
      } else {
        group synchronized {
          if (group.is(Dead)) {// 检测Consumer Group的状态
            // if the group is marked as dead, it means some other thread has just removed the group
            // from the coordinator metadata; this is likely that the group has migrated to some other
            // coordinator OR the group is in a transient unstable phase. Let the member retry
            // joining without the specified member id,
            responseCallback(Errors.UNKNOWN_MEMBER_ID.code)
          } else if (!group.is(Stable)) {
            responseCallback(Errors.REBALANCE_IN_PROGRESS.code)
          } else if (!group.has(memberId)) {// 检测MemberId
            responseCallback(Errors.UNKNOWN_MEMBER_ID.code)
          } else if (generationId != group.generationId) {// 检测generationId
            responseCallback(Errors.ILLEGAL_GENERATION.code)
          } else {
            val member = group.get(memberId)
            // 调用方法，继续下一步骤
            completeAndScheduleNextHeartbeatExpiration(group, member)
            responseCallback(Errors.NONE.code)
          }
        }
      }
    }
  }

  def handleCommitOffsets(groupId: String,
                          memberId: String,
                          generationId: Int,
                          offsetMetadata: immutable.Map[TopicPartition, OffsetAndMetadata],
                          responseCallback: immutable.Map[TopicPartition, Short] => Unit) {
    var delayedOffsetStore: Option[DelayedStore] = None

    if (!isActive.get) {
      responseCallback(offsetMetadata.mapValues(_ => Errors.GROUP_COORDINATOR_NOT_AVAILABLE.code))
    } else if (!isCoordinatorForGroup(groupId)) {
      responseCallback(offsetMetadata.mapValues(_ => Errors.NOT_COORDINATOR_FOR_GROUP.code))
    } else if (isCoordinatorLoadingInProgress(groupId)) {
      responseCallback(offsetMetadata.mapValues(_ => Errors.GROUP_LOAD_IN_PROGRESS.code))
    } else {
      val group = groupManager.getGroup(groupId)
      if (group == null) {
        if (generationId < 0)
          // the group is not relying on Kafka for partition management, so allow the commit
          delayedOffsetStore = Some(groupManager.prepareStoreOffsets(groupId, memberId, generationId, offsetMetadata,
            responseCallback))
        else
          // the group has failed over to this coordinator (which will be handled in KAFKA-2017),
          // or this is a request coming from an older generation. either way, reject the commit
          responseCallback(offsetMetadata.mapValues(_ => Errors.ILLEGAL_GENERATION.code))
      } else {
        group synchronized {
          if (group.is(Dead)) {
            responseCallback(offsetMetadata.mapValues(_ => Errors.UNKNOWN_MEMBER_ID.code))
          } else if (group.is(AwaitingSync)) {
            responseCallback(offsetMetadata.mapValues(_ => Errors.REBALANCE_IN_PROGRESS.code))
          } else if (!group.has(memberId)) {
            responseCallback(offsetMetadata.mapValues(_ => Errors.UNKNOWN_MEMBER_ID.code))
          } else if (generationId != group.generationId) {
            responseCallback(offsetMetadata.mapValues(_ => Errors.ILLEGAL_GENERATION.code))
          } else {
            val member = group.get(memberId)
            completeAndScheduleNextHeartbeatExpiration(group, member)
            delayedOffsetStore = Some(groupManager.prepareStoreOffsets(groupId, memberId, generationId,
              offsetMetadata, responseCallback))
          }
        }
      }
    }

    // store the offsets without holding the group lock
    delayedOffsetStore.foreach(groupManager.store)
  }

  def handleFetchOffsets(groupId: String,
                         partitions: Seq[TopicPartition]): Map[TopicPartition, OffsetFetchResponse.PartitionData] = {
    if (!isActive.get) {
      partitions.map { case topicPartition =>
        (topicPartition, new OffsetFetchResponse.PartitionData(OffsetFetchResponse.INVALID_OFFSET, "", Errors.GROUP_COORDINATOR_NOT_AVAILABLE.code))}.toMap
      // 检测GroupCoordinator是否是Consumer Group的管理者
    } else if (!isCoordinatorForGroup(groupId)) {
      partitions.map { case topicPartition =>
        (topicPartition, new OffsetFetchResponse.PartitionData(OffsetFetchResponse.INVALID_OFFSET, "", Errors.NOT_COORDINATOR_FOR_GROUP.code))}.toMap
    }
    // 检测GroupMetadata是否已完成加载
    else if (isCoordinatorLoadingInProgress(groupId)) {
      partitions.map { case topicPartition =>
        (topicPartition, new OffsetFetchResponse.PartitionData(OffsetFetchResponse.INVALID_OFFSET, "", Errors.GROUP_LOAD_IN_PROGRESS.code))}.toMap
    } else {
      // return offsets blindly regardless the current group state since the group may be using
      // Kafka commit storage without automatic group management
      // 交给GroupMetadataManager处理
      groupManager.getOffsets(groupId, partitions)
    }
  }

  def handleListGroups(): (Errors, List[GroupOverview]) = {
    if (!isActive.get) {
      (Errors.GROUP_COORDINATOR_NOT_AVAILABLE, List[GroupOverview]())
    } else {
      val errorCode = if (groupManager.isLoading()) Errors.GROUP_LOAD_IN_PROGRESS else Errors.NONE
      (errorCode, groupManager.currentGroups.map(_.overview).toList)
    }
  }

  def handleDescribeGroup(groupId: String): (Errors, GroupSummary) = {
    if (!isActive.get) {
      (Errors.GROUP_COORDINATOR_NOT_AVAILABLE, GroupCoordinator.EmptyGroup)
    } else if (!isCoordinatorForGroup(groupId)) {
      (Errors.NOT_COORDINATOR_FOR_GROUP, GroupCoordinator.EmptyGroup)
    } else if (isCoordinatorLoadingInProgress(groupId)) {
      (Errors.GROUP_LOAD_IN_PROGRESS, GroupCoordinator.EmptyGroup)
    } else {
      val group = groupManager.getGroup(groupId)
      if (group == null) {
        (Errors.NONE, GroupCoordinator.DeadGroup)
      } else {
        group synchronized {
          (Errors.NONE, group.summary)
        }
      }
    }
  }

  private def onGroupUnloaded(group: GroupMetadata) {
    group synchronized {
      info(s"Unloading group metadata for ${group.groupId} with generation ${group.generationId}")
      val previousState = group.currentState
      group.transitionTo(Dead)

      previousState match {
        case Dead =>
        case PreparingRebalance =>
          for (member <- group.allMemberMetadata) {
            if (member.awaitingJoinCallback != null) {
              member.awaitingJoinCallback(joinError(member.memberId, Errors.NOT_COORDINATOR_FOR_GROUP.code))
              member.awaitingJoinCallback = null
            }
          }
          joinPurgatory.checkAndComplete(GroupKey(group.groupId))

        case Stable | AwaitingSync =>
          for (member <- group.allMemberMetadata) {
            if (member.awaitingSyncCallback != null) {
              member.awaitingSyncCallback(Array.empty[Byte], Errors.NOT_COORDINATOR_FOR_GROUP.code)
              member.awaitingSyncCallback = null
            }
            heartbeatPurgatory.checkAndComplete(MemberKey(member.groupId, member.memberId))
          }
      }
    }
  }

  private def onGroupLoaded(group: GroupMetadata) {
    group synchronized {
      info(s"Loading group metadata for ${group.groupId} with generation ${group.generationId}")
      assert(group.is(Stable))
      group.allMemberMetadata.foreach(completeAndScheduleNextHeartbeatExpiration(group, _))
    }
  }

  // 当broker成为Offsets Topic分区的leader副本时，会回调这个函数
  def handleGroupImmigration(offsetTopicPartitionId: Int) {
    groupManager.loadGroupsForPartition(offsetTopicPartitionId, onGroupLoaded)
  }

  // d当broker成为Offsets Topic分区的follower副本时会回到这个方法进行清除工作
  def handleGroupEmigration(offsetTopicPartitionId: Int) {
    groupManager.removeGroupsForPartition(offsetTopicPartitionId, onGroupUnloaded)
  }

  /**
    * 步骤一：将分配结果更新到GroupMetadata维护的每个MemberMetadata中
    * 步骤二：调用每个MemberMetadata的awaitingSyncCallBack回调函数，创建SyncGroupResponse对象
    *         并添加到RequestChannel中等待发送
    * 步骤三：将本次心跳延迟任务完成并开始下次等待心跳的延迟任务的执行或超时
    */
  private def setAndPropagateAssignment(group: GroupMetadata, assignment: Map[String, Array[Byte]]) {
    assert(group.is(AwaitingSync))
    // 步骤一
    group.allMemberMetadata.foreach(member => member.assignment = assignment(member.memberId))
    // 步骤二三
    propagateAssignment(group, Errors.NONE.code)
  }

  private def resetAndPropagateAssignmentError(group: GroupMetadata, errorCode: Short) {
    assert(group.is(AwaitingSync))
    // 清空所有MemberMetadata的assignment字段
    group.allMemberMetadata.foreach(_.assignment = Array.empty[Byte])
    propagateAssignment(group, errorCode)
  }

  private def propagateAssignment(group: GroupMetadata, errorCode: Short) {
    for (member <- group.allMemberMetadata) {
      if (member.awaitingSyncCallback != null) {
        // 步骤二：调用awaitingSyncCallback回调函数，向对应的Consumer发送SyncGroupResponse
        member.awaitingSyncCallback(member.assignment, errorCode)
        member.awaitingSyncCallback = null// 清空回调函数

        // reset the session timeout for members after propagating the member's assignment.
        // This is because if any member's session expired while we were still awaiting either
        // the leader sync group or the storage callback, its expiration will be ignored and no
        // future heartbeat expectations will not be scheduled.
        // 步骤三：开启等待下次心跳的延迟任务
        completeAndScheduleNextHeartbeatExpiration(group, member)
      }
    }
  }

  private def validGroupId(groupId: String): Boolean = {
    groupId != null && !groupId.isEmpty
  }

  private def joinError(memberId: String, errorCode: Short): JoinGroupResult = {
    JoinGroupResult(
      members=Map.empty,
      memberId=memberId,
      generationId=0,
      subProtocol=GroupCoordinator.NoProtocol,
      leaderId=GroupCoordinator.NoLeader,
      errorCode=errorCode)
  }

  /**
   * Complete existing DelayedHeartbeats for the given member and schedule the next one
    * 更新收到此Member心跳的时间戳
    * 尝试执行对应的DelayedHeartbeat，并创建新的DelayedHeartbeat对象放入heartbeatPurgatory中，等待下次心跳到来或DelayedHeartbeat超时
   */
  private def completeAndScheduleNextHeartbeatExpiration(group: GroupMetadata, member: MemberMetadata) {
    // complete current heartbeat expectation
    // 更新心跳时间
    member.latestHeartbeat = time.milliseconds()
    // 获取DelayedHeartbeat的key
    val memberKey = MemberKey(member.groupId, member.memberId)
    // 尝试完成之前添加的DelayedHeartbeat
    heartbeatPurgatory.checkAndComplete(memberKey)

    // reschedule the next heartbeat expiration deadline
    // 计算下一次的Heartbeat的超时时间
    val newHeartbeatDeadline = member.latestHeartbeat + member.sessionTimeoutMs
    // 创建新的DelayedHeartbeat对象，并添加到heartbeatPurgatory中管理
    val delayedHeartbeat = new DelayedHeartbeat(this, group, member, newHeartbeatDeadline, member.sessionTimeoutMs)
    heartbeatPurgatory.tryCompleteElseWatch(delayedHeartbeat, Seq(memberKey))
  }

  private def removeHeartbeatForLeavingMember(group: GroupMetadata, member: MemberMetadata) {
    member.isLeaving = true
    val memberKey = MemberKey(member.groupId, member.memberId)
    heartbeatPurgatory.checkAndComplete(memberKey)
  }

  // 添加Member信息
  private def addMemberAndRebalance(sessionTimeoutMs: Int,
                                    clientId: String,
                                    clientHost: String,
                                    protocols: List[(String, Array[Byte])],
                                    group: GroupMetadata,
                                    callback: JoinCallback) = {
    // use the client-id with a random id suffix as the member-id
    // memberId有clientId与UUID构成
    val memberId = clientId + "-" + group.generateMemberIdSuffix
    // 创建新的MemberMetadata对象
    val member = new MemberMetadata(memberId, group.groupId, clientId, clientHost, sessionTimeoutMs, protocols)
    // 设置awaitingJoinCallback，该回调函数是KafkaApis.handleJoinGroupRequest()方法中定义的sendResponseCallback
    member.awaitingJoinCallback = callback
    group.add(member.memberId, member)// 添加到GroupMetadata中保存
    maybePrepareRebalance(group)// 尝试进行状态切换
    member
  }

  // 更新Member信息
  private def updateMemberAndRebalance(group: GroupMetadata,
                                       member: MemberMetadata,
                                       protocols: List[(String, Array[Byte])],
                                       callback: JoinCallback) {
    // 更新MemberMetadata支持的协议和awaitingJoinCallback回调函数
    member.supportedProtocols = protocols
    member.awaitingJoinCallback = callback
    // 尝试进行状态切换
    maybePrepareRebalance(group)
  }

  private def maybePrepareRebalance(group: GroupMetadata) {
    group synchronized {
      if (group.canRebalance)
        prepareRebalance(group)
    }
  }

  // 状态切换为PreparingRebalance，并创建相应的DelayedJoin
  private def prepareRebalance(group: GroupMetadata) {
    // if any members are awaiting sync, cancel their request and have them rejoin
    // 如果处于AwaitingSync状态，则先要重置MemberMetadata.assignment字段
    // 并回调awaitingSyncCallback向消费者返回REBALANCE_IN_PROGRESS的错误码
    if (group.is(AwaitingSync))
      resetAndPropagateAssignmentError(group, Errors.REBALANCE_IN_PROGRESS.code)

    // 将Consumer Group状态转换为PreparingRebalance状态
    group.transitionTo(PreparingRebalance)
    info("Preparing to restabilize group %s with old generation %s".format(group.groupId, group.generationId))

    // DelayedJoin的超时时长是GroupMetadata中所有Member设置的超时时长的最大值
    val rebalanceTimeout = group.rebalanceTimeout
    // 创建DelayedJoin对象
    val delayedRebalance = new DelayedJoin(this, group, rebalanceTimeout)
    // 创建DelayedJoin的key
    val groupKey = GroupKey(group.groupId)
    // 尝试立即完成DelayedJoin，否则将DelayedFetch添加到joinPurgatory中
    joinPurgatory.tryCompleteElseWatch(delayedRebalance, Seq(groupKey))
  }

  private def onMemberFailure(group: GroupMetadata, member: MemberMetadata) {
    trace("Member %s in group %s has failed".format(member.memberId, group.groupId))
    // 将对应的Member从GroupMetadata中删除
    group.remove(member.memberId)
    group.currentState match {
      // 不做任何处理
      case Dead =>
      // 之前的分区可能已经失效了，将GroupMetadata切换成PreparingRebalance状态
      case Stable | AwaitingSync => maybePrepareRebalance(group)
      // GroupMetadata中的Member减少，可能满足DelayedJoin的执行条件，尝试执行
      case PreparingRebalance => joinPurgatory.checkAndComplete(GroupKey(group.groupId))
    }
  }

  // 判断GroupMetadata中的已知Member是否已重新申请加入来决定DelayedJoin是否符合执行条件
  def tryCompleteJoin(group: GroupMetadata, forceComplete: () => Boolean) = {
    group synchronized {
      // 判断已知Member是否已经申请加入
      if (group.notYetRejoinedMembers.isEmpty)
        forceComplete()
      else false
    }
  }

  def onExpireJoin() {
    // TODO: add metrics for restabilize timeouts
  }

  // 已知Member都已申请重新加入或DelayedJoin到期时，都会执行这个方法
  /**
    * 1、首先，将未重新申请加入的已知Member删除
    * 2、如果GroupMetadata中不再包含任何Member，则将Consume Group转换成Dead状态，删除对应的GroupMetadata对象并向Offsets Topic中对应的分区写入删除标记
    * 3、之后，更新generationId，调用awaitingJoinCallback回调函数发送JoinGroupResponse
    * @param group
    */
  def onCompleteJoin(group: GroupMetadata) {
    group synchronized {
      // 获取未重新申请加入的已知Member的集合
      val failedMembers = group.notYetRejoinedMembers
      if (group.isEmpty || !failedMembers.isEmpty) {
        failedMembers.foreach { failedMember =>
          // 移除未加入的已知Member
          group.remove(failedMember.memberId)
          // TODO: cut the socket connection to the client
        }

        // TODO KAFKA-2720: only remove group in the background thread
        // 如果GroupMetadata中已经没有Member，则将GroupMetadata切换成Dead状态
        if (group.isEmpty) {
          group.transitionTo(Dead)
          // 删除GroupMetadata对象，并在对应的Offsets Topic Partition中添加删除标记
          groupManager.removeGroup(group)
          info("Group %s generation %s is dead and removed".format(group.groupId, group.generationId))
        }
      }
      if (!group.is(Dead)) {
        // generationId递增，选择该Consumer Group最终使用的PartitionAssignor
        group.initNextGeneration()
        info("Stabilized group %s generation %s".format(group.groupId, group.generationId))

        // trigger the awaiting join group response callback for all the members after rebalancing
        // 向GroupMetadata中所有的Member发送JoinGroupResponse
        for (member <- group.allMemberMetadata) {
          assert(member.awaitingJoinCallback != null)
          val joinResult = JoinGroupResult(
            // 发送给Group leader和follower的JoinGroupResponse内容不同
            members=if (member.memberId == group.leaderId) { group.currentMemberMetadata } else { Map.empty },
            memberId=member.memberId,
            generationId=group.generationId,
            subProtocol=group.protocol,
            leaderId=group.leaderId,
            errorCode=Errors.NONE.code)

          // 调用回调函数，既KafkaApis.handleJoinGroupRequest方法中定义的sendResponseCallback，将响应放到RequestChannel中等待发送
          member.awaitingJoinCallback(joinResult)
          member.awaitingJoinCallback = null
          // 心跳操作
          completeAndScheduleNextHeartbeatExpiration(group, member)
        }
      }
    }
  }

  /**
    * 检测四个条件，满足其中任意一条，则认为DelayedHeartbeat符合执行条件
    *   1、最后一次收到心跳信息的时间与heartbeatDeadline的差距大于sessionTimeout
    *   2、awaitingJoinCallback不为空，既消费者正在等待JoinGroupResponse
    *   3、awaitingSyncCallback不为空，既消费正在等待SyncGroupResponse
    *   4、消费者已经离开了Consumer Group
    */
  def tryCompleteHeartbeat(group: GroupMetadata, member: MemberMetadata, heartbeatDeadline: Long, forceComplete: () => Boolean) = {
    group synchronized {
      if (shouldKeepMemberAlive(member, heartbeatDeadline) || member.isLeaving)// 检测条件1-3// 检测4
        forceComplete()
      else false
    }
  }

  // DelayedHeartbeat到期还会执行这个方法
  // 将其对应的Member从GroupMetadata中删除，并按照当前GroupMetadata所处的状态进行分类处理
  def onExpireHeartbeat(group: GroupMetadata, member: MemberMetadata, heartbeatDeadline: Long) {
    group synchronized {
      if (!shouldKeepMemberAlive(member, heartbeatDeadline))
        onMemberFailure(group, member)// Member下线后的相关处理操作
    }
  }

  // 空实现，DelayedHeartbeat执行之后，仅会将其从heartbeatPurgatory中删除，并不会进行其他操作
  def onCompleteHeartbeat() {
    // TODO: add metrics for complete heartbeats
  }

  def partitionFor(group: String): Int = groupManager.partitionFor(group)

  private def shouldKeepMemberAlive(member: MemberMetadata, heartbeatDeadline: Long) =
    // 条件二
    member.awaitingJoinCallback != null ||
      // 条件三
      member.awaitingSyncCallback != null ||
      // 条件一
      member.latestHeartbeat + member.sessionTimeoutMs > heartbeatDeadline

  private def isCoordinatorForGroup(groupId: String) = groupManager.isGroupLocal(groupId)

  private def isCoordinatorLoadingInProgress(groupId: String) = groupManager.isGroupLoading(groupId)
}

object GroupCoordinator {

  val NoState = ""
  val NoProtocolType = ""
  val NoProtocol = ""
  val NoLeader = ""
  val NoMembers = List[MemberSummary]()
  val EmptyGroup = GroupSummary(NoState, NoProtocolType, NoProtocol, NoMembers)
  val DeadGroup = GroupSummary(Dead.toString, NoProtocolType, NoProtocol, NoMembers)

  def apply(config: KafkaConfig,
            zkUtils: ZkUtils,
            replicaManager: ReplicaManager,
            time: Time): GroupCoordinator = {
    val heartbeatPurgatory = DelayedOperationPurgatory[DelayedHeartbeat]("Heartbeat", config.brokerId)
    val joinPurgatory = DelayedOperationPurgatory[DelayedJoin]("Rebalance", config.brokerId)
    apply(config, zkUtils, replicaManager, heartbeatPurgatory, joinPurgatory, time)
  }

  def apply(config: KafkaConfig,
            zkUtils: ZkUtils,
            replicaManager: ReplicaManager,
            heartbeatPurgatory: DelayedOperationPurgatory[DelayedHeartbeat],
            joinPurgatory: DelayedOperationPurgatory[DelayedJoin],
            time: Time): GroupCoordinator = {
    val offsetConfig = OffsetConfig(maxMetadataSize = config.offsetMetadataMaxSize,
      loadBufferSize = config.offsetsLoadBufferSize,
      offsetsRetentionMs = config.offsetsRetentionMinutes * 60 * 1000L,
      offsetsRetentionCheckIntervalMs = config.offsetsRetentionCheckIntervalMs,
      offsetsTopicNumPartitions = config.offsetsTopicPartitions,
      offsetsTopicSegmentBytes = config.offsetsTopicSegmentBytes,
      offsetsTopicReplicationFactor = config.offsetsTopicReplicationFactor,
      offsetsTopicCompressionCodec = config.offsetsTopicCompressionCodec,
      offsetCommitTimeoutMs = config.offsetCommitTimeoutMs,
      offsetCommitRequiredAcks = config.offsetCommitRequiredAcks)
    val groupConfig = GroupConfig(groupMinSessionTimeoutMs = config.groupMinSessionTimeoutMs,
      groupMaxSessionTimeoutMs = config.groupMaxSessionTimeoutMs)

    val groupMetadataManager = new GroupMetadataManager(config.brokerId, offsetConfig, replicaManager, zkUtils, time)
    new GroupCoordinator(config.brokerId, groupConfig, offsetConfig, groupMetadataManager, heartbeatPurgatory, joinPurgatory, time)
  }

}
