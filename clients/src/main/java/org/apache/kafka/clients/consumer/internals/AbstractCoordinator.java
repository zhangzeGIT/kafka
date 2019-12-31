/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.clients.consumer.internals;

import org.apache.kafka.clients.ClientResponse;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.GroupAuthorizationException;
import org.apache.kafka.common.errors.GroupCoordinatorNotAvailableException;
import org.apache.kafka.common.errors.IllegalGenerationException;
import org.apache.kafka.common.errors.RebalanceInProgressException;
import org.apache.kafka.common.errors.UnknownMemberIdException;
import org.apache.kafka.common.metrics.Measurable;
import org.apache.kafka.common.metrics.MetricConfig;
import org.apache.kafka.common.metrics.Metrics;
import org.apache.kafka.common.metrics.Sensor;
import org.apache.kafka.common.metrics.stats.Avg;
import org.apache.kafka.common.metrics.stats.Count;
import org.apache.kafka.common.metrics.stats.Max;
import org.apache.kafka.common.metrics.stats.Rate;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.protocol.Errors;
import org.apache.kafka.common.requests.GroupCoordinatorRequest;
import org.apache.kafka.common.requests.GroupCoordinatorResponse;
import org.apache.kafka.common.requests.HeartbeatRequest;
import org.apache.kafka.common.requests.HeartbeatResponse;
import org.apache.kafka.common.requests.JoinGroupRequest;
import org.apache.kafka.common.requests.JoinGroupRequest.ProtocolMetadata;
import org.apache.kafka.common.requests.JoinGroupResponse;
import org.apache.kafka.common.requests.LeaveGroupRequest;
import org.apache.kafka.common.requests.LeaveGroupResponse;
import org.apache.kafka.common.requests.OffsetCommitRequest;
import org.apache.kafka.common.requests.SyncGroupRequest;
import org.apache.kafka.common.requests.SyncGroupResponse;
import org.apache.kafka.common.utils.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * AbstractCoordinator implements group management for a single group member by interacting with
 * a designated Kafka broker (the coordinator). Group semantics are provided by extending this class.
 * See {@link ConsumerCoordinator} for example usage.
 *
 * From a high level, Kafka's group management protocol consists of the following sequence of actions:
 *
 * <ol>
 *     <li>Group Registration: Group members register with the coordinator providing their own metadata
 *         (such as the set of topics they are interested in).</li>
 *     <li>Group/Leader Selection: The coordinator select the members of the group and chooses one member
 *         as the leader.</li>
 *     <li>State Assignment: The leader collects the metadata from all the members of the group and
 *         assigns state.</li>
 *     <li>Group Stabilization: Each member receives the state assigned by the leader and begins
 *         processing.</li>
 * </ol>
 *
 * To leverage this protocol, an implementation must define the format of metadata provided by each
 * member for group registration in {@link #metadata()} and the format of the state assignment provided
 * by the leader in {@link #performAssignment(String, String, Map)} and becomes available to members in
 * {@link #onJoinComplete(int, String, String, ByteBuffer)}.
 *
 */
public abstract class AbstractCoordinator implements Closeable {

    private static final Logger log = LoggerFactory.getLogger(AbstractCoordinator.class);

    // 心跳任务辅助类，
    private final Heartbeat heartbeat;
    // 定时任务，负责定时发送心跳请求和心跳响应的处理
    // 会被添加到ConsumerNetworkClient.delayedTasks定时任务队列中
    private final HeartbeatTask heartbeatTask;
    private final int sessionTimeoutMs;
    private final GroupCoordinatorMetrics sensors;
    // 当前消费者所属的ConsumerGroup的ID
    protected final String groupId;
    // 负责网络通讯和执行定时任务
    protected final ConsumerNetworkClient client;
    protected final Time time;
    protected final long retryBackoffMs;

    // 是否需要执行发送JoinGroupRequest请求前的准备操作
    private boolean needsJoinPrepare = true;
    // 是否重新发送JoinGroupRequest请求的条件之一
    // 正常收到JoinGroupResponse，将此设置成false
    // 收到异常SyncGroupResponse或HeartbeatResponse或消费这里开ConsumerGroup时执行，设置成true
    private boolean rejoinNeeded = true;
    // 服务端GroupCoordinator所在的node节点
    protected Node coordinator;
    // 服务端GroupCoordinator返回的分配给消费者的唯一ID
    protected String memberId;
    protected String protocol;
    // 服务端GroupCoordinator返回的年代信息，用来区分两次Rebalance操作
    // 每次Rebalance会递增
    protected int generation;

    /**
     * Initialize the coordination manager.
     */
    public AbstractCoordinator(ConsumerNetworkClient client,
                               String groupId,
                               int sessionTimeoutMs,
                               int heartbeatIntervalMs,
                               Metrics metrics,
                               String metricGrpPrefix,
                               Time time,
                               long retryBackoffMs) {
        this.client = client;
        this.time = time;
        this.generation = OffsetCommitRequest.DEFAULT_GENERATION_ID;
        this.memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID;
        this.groupId = groupId;
        this.coordinator = null;
        this.sessionTimeoutMs = sessionTimeoutMs;
        this.heartbeat = new Heartbeat(this.sessionTimeoutMs, heartbeatIntervalMs, time.milliseconds());
        this.heartbeatTask = new HeartbeatTask();
        this.sensors = new GroupCoordinatorMetrics(metrics, metricGrpPrefix);
        this.retryBackoffMs = retryBackoffMs;
    }

    /**
     * Unique identifier for the class of protocols implements (e.g. "consumer" or "connect").
     * @return Non-null protocol type name
     */
    protected abstract String protocolType();

    /**
     * Get the current list of protocols and their associated metadata supported
     * by the local member. The order of the protocols in the list indicates the preference
     * of the protocol (the first entry is the most preferred). The coordinator takes this
     * preference into account when selecting the generation protocol (generally more preferred
     * protocols will be selected as long as all members support them and there is no disagreement
     * on the preference).
     * @return Non-empty map of supported protocols and metadata
     */
    protected abstract List<ProtocolMetadata> metadata();

    /**
     * Invoked prior to each group join or rejoin. This is typically used to perform any
     * cleanup from the previous generation (such as committing offsets for the consumer)
     * @param generation The previous generation or -1 if there was none
     * @param memberId The identifier of this member in the previous group or "" if there was none
     */
    protected abstract void onJoinPrepare(int generation, String memberId);

    /**
     * Perform assignment for the group. This is used by the leader to push state to all the members
     * of the group (e.g. to push partition assignments in the case of the new consumer)
     * @param leaderId The id of the leader (which is this member)
     * @param allMemberMetadata Metadata from all members of the group
     * @return A map from each member to their state assignment
     */
    protected abstract Map<String, ByteBuffer> performAssignment(String leaderId,
                                                                 String protocol,
                                                                 Map<String, ByteBuffer> allMemberMetadata);

    /**
     * Invoked when a group member has successfully joined a group.
     * @param generation The generation that was joined
     * @param memberId The identifier for the local member in the group
     * @param protocol The protocol selected by the coordinator
     * @param memberAssignment The assignment propagated from the group leader
     */
    protected abstract void onJoinComplete(int generation,
                                           String memberId,
                                           String protocol,
                                           ByteBuffer memberAssignment);

    /**
     * Block until the coordinator for this group is known and is ready to receive requests.
     */
    // 发送GroupCoordinatorRequest请求的入口
    public void ensureCoordinatorReady() {
        // 检查是否需要重新查找GroupCoordinator
        while (coordinatorUnknown()) {
            // 查找集群负载最低的节点，并创建GroupCoordinatorRequest
            RequestFuture<Void> future = sendGroupCoordinatorRequest();
            // 创建并缓存请求
            // 阻塞发送GroupCoordinatorRequest，并处理GroupCoordinatorResponse
            client.poll(future);
            // 异常处理
            if (future.failed()) {
                // 如果是RetriableException，则阻塞更新metadata，跳转到while循环开始
                if (future.isRetriable())
                    client.awaitMetadataUpdate();
                else
                    // 其他异常直接报错
                    throw future.exception();
                // 如果成功找到了GroupCoordinator节点，但网络连接失败
                // 则将其unsent中对应的请求清空，并将coordinator字段设置为null
                // 退避一段时间，跳转到while
            } else if (coordinator != null && client.connectionFailed(coordinator)) {
                // we found the coordinator, but the connection has failed, so mark
                // it dead and backoff before retrying discovery
                coordinatorDead();
                time.sleep(retryBackoffMs);
            }

        }
    }

    /**
     * Check whether the group should be rejoined (e.g. if metadata changes)
     * @return true if it should, false otherwise
     */
    protected boolean needRejoin() {
        return rejoinNeeded;
    }

    /**
     * Ensure that the group is active (i.e. joined and synced)
     */
    public void ensureActiveGroup() {
        // 步骤三：判断是否使用了AUTO_TOPICS或AUTO_PATTERN，是否发送JoinGroupRequest请求
        if (!needRejoin())
            return;

        if (needsJoinPrepare) {
            // 步骤四：发送JoinGroupRequest请求前的准备工作
            onJoinPrepare(generation, memberId);
            needsJoinPrepare = false;
        }

        // 再次调用
        while (needRejoin()) {
            // 如果还有发往GroupCoordinator所在node的请求，则阻塞等待这些请求全部发送完成并收到响应
            // 即等待unsent及InFlightRequests的队列为空
            // 步骤五：检测GroupCoordinator状态
            ensureCoordinatorReady();

            // ensure that there are no pending requests to the coordinator. This is important
            // in particular to avoid resending a pending JoinGroup request.
            if (client.pendingRequestCount(this.coordinator) > 0) {
                // 等待发往GroupCoordinator所在节点的消息全部完成
                client.awaitPendingRequests(this.coordinator);
                continue;
            }

            // 步骤七：创建JoinGroupRequest，并调用ConsumerNetworkClient.sent方法将请求放入unsent
            RequestFuture<ByteBuffer> future = sendJoinGroupRequest();
            // 步骤八：在上一步骤中的future加入listener
            future.addListener(new RequestFutureListener<ByteBuffer>() {
                @Override
                public void onSuccess(ByteBuffer value) {
                    // handle join completion in the callback so that the callback will be invoked
                    // even if the consumer is woken up before finishing the rebalance
                    onJoinComplete(generation, memberId, protocol, value);
                    // 修改needsJoinPrepare，为下次Rebalance做准备
                    needsJoinPrepare = true;
                    // 开启HeartbeatTask
                    heartbeatTask.reset();
                }

                @Override
                public void onFailure(RuntimeException e) {
                    // we handle failures below after the request finishes. if the join completes
                    // after having been woken up, the exception is ignored and we will rejoin
                }
            });
            // 步骤九，调用ConsumerNetworkClient。pool方法发送JoinGroupRequest，这会阻塞等待
            // 直到收到JoinGroupResponse或出现异常
            client.poll(future);

            // 检测，如果出现RetriableException则重试
            if (future.failed()) {
                RuntimeException exception = future.exception();
                if (exception instanceof UnknownMemberIdException ||
                        exception instanceof RebalanceInProgressException ||
                        exception instanceof IllegalGenerationException)
                    continue;
                else if (!future.isRetriable())
                    throw exception;
                // 退避一段时间重试
                time.sleep(retryBackoffMs);
            }
        }
    }

    private class HeartbeatTask implements DelayedTask {

        private boolean requestInFlight = false;

        public void reset() {
            // start or restart the heartbeat task to be executed at the next chance
            long now = time.milliseconds();
            heartbeat.resetSessionTimeout(now);
            client.unschedule(this);

            if (!requestInFlight)
                client.schedule(this, now);
        }

        @Override
        public void run(final long now) {
            // 步骤一：检测是否发送心跳
            if (generation < 0 || needRejoin() || coordinatorUnknown()) {
                // no need to send the heartbeat we're not using auto-assignment or if we are
                // awaiting a rebalance
                return;
            }

            // 步骤二：上次心跳响应超时，将GroupCoordinator标记为宕机
            if (heartbeat.sessionTimeoutExpired(now)) {
                // we haven't received a successful heartbeat in one session interval
                // so mark the coordinator dead
                coordinatorDead();
                return;
            }

            // 步骤三：没有到发送心跳请求的时间
            if (!heartbeat.shouldHeartbeat(now)) {
                // we don't need to heartbeat now, so reschedule for when we do
                client.schedule(this, now + heartbeat.timeToNextHeartbeat(now));
            } else {
                // 步骤四：更新发送HeartbeatRequest的时间
                heartbeat.sentHeartbeat(now);
                // 防止重复发送HeartbeatRequest
                requestInFlight = true;

                // 步骤五：创建并缓存HeartbeatRequest
                RequestFuture<Void> future = sendHeartbeatRequest();
                // 步骤六：添加监听器
                future.addListener(new RequestFutureListener<Void>() {
                    @Override
                    public void onSuccess(Void value) {
                        requestInFlight = false;
                        // 更新Heartbeat记录的时间
                        long now = time.milliseconds();
                        heartbeat.receiveHeartbeat(now);
                        long nextHeartbeatTime = now + heartbeat.timeToNextHeartbeat(now);
                        // 重新调度HeartbeatTask
                        client.schedule(HeartbeatTask.this, nextHeartbeatTime);
                    }

                    @Override
                    public void onFailure(RuntimeException e) {
                        requestInFlight = false;
                        client.schedule(HeartbeatTask.this, time.milliseconds() + retryBackoffMs);
                    }
                });
            }
        }
    }

    /**
     * Join the group and return the assignment for the next generation. This function handles both
     * JoinGroup and SyncGroup, delegating to {@link #performAssignment(String, String, Map)} if
     * elected leader by the coordinator.
     * @return A request future which wraps the assignment returned from the group leader
     */
    private RequestFuture<ByteBuffer> sendJoinGroupRequest() {
        // 检测GroupCoordinator
        if (coordinatorUnknown())
            return RequestFuture.coordinatorNotAvailable();

        // send a join group request to the coordinator
        log.info("(Re-)joining group {}", groupId);

        // 创建JoinGroupRequest
        JoinGroupRequest request = new JoinGroupRequest(
                groupId,
                this.sessionTimeoutMs,
                this.memberId,
                protocolType(),
                metadata());

        log.debug("Sending JoinGroup ({}) to coordinator {}", request, this.coordinator);

        // 将JoinGroupRequest放入unsent集合等待发送
        return client.send(coordinator, ApiKeys.JOIN_GROUP, request)
                .compose(new JoinGroupResponseHandler());
    }


    private class JoinGroupResponseHandler extends CoordinatorResponseHandler<JoinGroupResponse, ByteBuffer> {

        @Override
        public JoinGroupResponse parse(ClientResponse response) {
            return new JoinGroupResponse(response.responseBody());
        }

        @Override
        public void handle(JoinGroupResponse joinResponse, RequestFuture<ByteBuffer> future) {
            Errors error = Errors.forCode(joinResponse.errorCode());
            if (error == Errors.NONE) {
                log.debug("Received successful join group response for group {}: {}", groupId, joinResponse.toStruct());
                // 步骤一，解析JoinGroupResponse，更新到本地
                AbstractCoordinator.this.memberId = joinResponse.memberId();
                AbstractCoordinator.this.generation = joinResponse.generationId();
                // 修改rejoinNeeded标记
                AbstractCoordinator.this.rejoinNeeded = false;
                AbstractCoordinator.this.protocol = joinResponse.groupProtocol();
                sensors.joinLatency.record(response.requestLatencyMs());
                // 步骤二，判断是否是leader
                if (joinResponse.isLeader()) {
                    // 此future是前面sendJoinGroupRequest方法返回的
                    // onJoinLeader和onJoinFollower方法中，都涉及发送SyncGroupRequest逻辑
                    // 当SyncGroupRequest处理完成后，再通知此future对象
                    onJoinLeader(joinResponse).chain(future);
                } else {
                    onJoinFollower().chain(future);
                }
            } else if (error == Errors.GROUP_LOAD_IN_PROGRESS) {
                log.debug("Attempt to join group {} rejected since coordinator {} is loading the group.", groupId,
                        coordinator);
                // backoff and retry
                future.raise(error);
            } else if (error == Errors.UNKNOWN_MEMBER_ID) {
                // reset the member id and retry immediately
                AbstractCoordinator.this.memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID;
                log.debug("Attempt to join group {} failed due to unknown member id.", groupId);
                future.raise(Errors.UNKNOWN_MEMBER_ID);
            } else if (error == Errors.GROUP_COORDINATOR_NOT_AVAILABLE
                    || error == Errors.NOT_COORDINATOR_FOR_GROUP) {
                // re-discover the coordinator and retry with backoff
                coordinatorDead();
                log.debug("Attempt to join group {} failed due to obsolete coordinator information: {}", groupId, error.message());
                future.raise(error);
            } else if (error == Errors.INCONSISTENT_GROUP_PROTOCOL
                    || error == Errors.INVALID_SESSION_TIMEOUT
                    || error == Errors.INVALID_GROUP_ID) {
                // log the error and re-throw the exception
                log.error("Attempt to join group {} failed due to fatal error: {}", groupId, error.message());
                future.raise(error);
            } else if (error == Errors.GROUP_AUTHORIZATION_FAILED) {
                future.raise(new GroupAuthorizationException(groupId));
            } else {
                // unexpected error, throw the exception
                future.raise(new KafkaException("Unexpected error in join group response: " + error.message()));
            }
        }
    }

    private RequestFuture<ByteBuffer> onJoinFollower() {
        // send follower's sync group with an empty assignment
        SyncGroupRequest request = new SyncGroupRequest(groupId, generation,
                memberId, Collections.<String, ByteBuffer>emptyMap());
        log.debug("Sending follower SyncGroup for group {} to coordinator {}: {}", groupId, this.coordinator, request);
        return sendSyncGroupRequest(request);
    }

    private RequestFuture<ByteBuffer> onJoinLeader(JoinGroupResponse joinResponse) {
        try {
            // perform the leader synchronization and send back the assignment for the group
            Map<String, ByteBuffer> groupAssignment = performAssignment(joinResponse.leaderId(), joinResponse.groupProtocol(),
                    joinResponse.members());

            // 完成分区分配，进入Synchronizing Group State阶段，创建并发送SyncGroupRequest，follow的GroupAssignment为空
            // request：分区分配结果  response：分配给当前消费者的分区
            SyncGroupRequest request = new SyncGroupRequest(groupId, generation, memberId, groupAssignment);
            log.debug("Sending leader SyncGroup for group {} to coordinator {}: {}", groupId, this.coordinator, request);
            return sendSyncGroupRequest(request);
        } catch (RuntimeException e) {
            return RequestFuture.failure(e);
        }
    }

    private RequestFuture<ByteBuffer> sendSyncGroupRequest(SyncGroupRequest request) {
        if (coordinatorUnknown())
            return RequestFuture.coordinatorNotAvailable();
        return client.send(coordinator, ApiKeys.SYNC_GROUP, request)
                .compose(new SyncGroupResponseHandler());
    }

    private class SyncGroupResponseHandler extends CoordinatorResponseHandler<SyncGroupResponse, ByteBuffer> {

        @Override
        public SyncGroupResponse parse(ClientResponse response) {
            return new SyncGroupResponse(response.responseBody());
        }

        @Override
        public void handle(SyncGroupResponse syncResponse,
                           RequestFuture<ByteBuffer> future) {
            Errors error = Errors.forCode(syncResponse.errorCode());
            if (error == Errors.NONE) {
                log.info("Successfully joined group {} with generation {}", groupId, generation);
                sensors.syncLatency.record(response.requestLatencyMs());
                // 调用RequestFuture.complete方法传播分区分配结果
                future.complete(syncResponse.memberAssignment());
            } else {
                // 将rejoinNeeded设置为true
                AbstractCoordinator.this.rejoinNeeded = true;
                // 异常处理
                if (error == Errors.GROUP_AUTHORIZATION_FAILED) {
                    future.raise(new GroupAuthorizationException(groupId));
                } else if (error == Errors.REBALANCE_IN_PROGRESS) {
                    log.debug("SyncGroup for group {} failed due to coordinator rebalance", groupId);
                    future.raise(error);
                } else if (error == Errors.UNKNOWN_MEMBER_ID
                        || error == Errors.ILLEGAL_GENERATION) {
                    log.debug("SyncGroup for group {} failed due to {}", groupId, error);
                    AbstractCoordinator.this.memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID;
                    future.raise(error);
                } else if (error == Errors.GROUP_COORDINATOR_NOT_AVAILABLE
                        || error == Errors.NOT_COORDINATOR_FOR_GROUP) {
                    log.debug("SyncGroup for group {} failed due to {}", groupId, error);
                    coordinatorDead();
                    future.raise(error);
                } else {
                    future.raise(new KafkaException("Unexpected error from SyncGroup: " + error.message()));
                }
            }
        }
    }

    /**
     * Discover the current coordinator for the group. Sends a GroupMetadata request to
     * one of the brokers. The returned future should be polled to get the result of the request.
     * @return A request future which indicates the completion of the metadata request
     */
    private RequestFuture<Void> sendGroupCoordinatorRequest() {
        // initiate the group metadata request
        // find a node to ask about the coordinator

        // 查找负载最低的节点，底层实现是查找InFlightRequest中未确认请求最少的节点
        Node node = this.client.leastLoadedNode();
        if (node == null) {
            // TODO: If there are no brokers left, perhaps we should use the bootstrap set
            // from configuration?
            // 找不到节点，直接返回异常结束
            return RequestFuture.noBrokersAvailable();
        } else {
            // create a group  metadata request
            log.debug("Sending coordinator request for group {} to broker {}", groupId, node);
            // 创建GroupCoordinatorRequest
            GroupCoordinatorRequest metadataRequest = new GroupCoordinatorRequest(this.groupId);
            // 添加到unsent集合，并且使用匿名的RequestFutureAdapter
            return client.send(node, ApiKeys.GROUP_COORDINATOR, metadataRequest)
                    .compose(new RequestFutureAdapter<ClientResponse, Void>() {
                        @Override
                        public void onSuccess(ClientResponse response, RequestFuture<Void> future) {
                            // 处理GroupMetadataResponse的入口
                            handleGroupMetadataResponse(response, future);
                        }
                    });
        }
    }

    private void handleGroupMetadataResponse(ClientResponse resp, RequestFuture<Void> future) {
        log.debug("Received group coordinator response {}", resp);

        // 检测是否找到GroupCoordinator且成功连接，如果是，则忽略
        if (!coordinatorUnknown()) {
            // We already found the coordinator, so ignore the request
            future.complete(null);
        } else {
            // 解析得到GroupCoordinatorResponse信息
            GroupCoordinatorResponse groupCoordinatorResponse = new GroupCoordinatorResponse(resp.responseBody());
            // use MAX_VALUE - node.id as the coordinator id to mimic separate connections
            // for the coordinator in the underlying network client layer
            // TODO: this needs to be better handled in KAFKA-1935
            Errors error = Errors.forCode(groupCoordinatorResponse.errorCode());
            if (error == Errors.NONE) {
                // 创建coordinator对应的node
                this.coordinator = new Node(Integer.MAX_VALUE - groupCoordinatorResponse.node().id(),
                        groupCoordinatorResponse.node().host(),
                        groupCoordinatorResponse.node().port());

                log.info("Discovered coordinator {} for group {}.", coordinator, groupId);

                // 尝试与GroupCoordinator建立连接
                client.tryConnect(coordinator);

                // start sending heartbeats only if we have a valid generation
                // 启动HeartbeatTask任务
                if (generation > 0)
                    heartbeatTask.reset();
                // 将正常收到GroupCoordinatorResponse的事件传播出去
                future.complete(null);
                // 如果错误不是none，则调用raise方法传播出去，最终由ensureCoordinatorReady方法处理
            } else if (error == Errors.GROUP_AUTHORIZATION_FAILED) {
                future.raise(new GroupAuthorizationException(groupId));
            } else {
                future.raise(error);
            }
        }
    }

    /**
     * Check if we know who the coordinator is and we have an active connection
     * @return true if the coordinator is unknown
     */
    public boolean coordinatorUnknown() {
        // 检测coordinator字段是否为空
        if (coordinator == null)
            return true;

        // 检测与GroupCoordinator之间的网络连接是否正常
        if (client.connectionFailed(coordinator)) {
            // 将unsent集合中对应的请求清空并将coordinator字段设置为null
            coordinatorDead();
            return true;
        }

        return false;
    }

    /**
     * Mark the current coordinator as dead.
     */
    protected void coordinatorDead() {
        if (this.coordinator != null) {
            log.info("Marking the coordinator {} dead for group {}", this.coordinator, groupId);
            client.failUnsentRequests(this.coordinator, GroupCoordinatorNotAvailableException.INSTANCE);
            this.coordinator = null;
        }
    }

    /**
     * Close the coordinator, waiting if needed to send LeaveGroup.
     */
    @Override
    public void close() {
        // we do not need to re-enable wakeups since we are closing already
        client.disableWakeups();
        maybeLeaveGroup();
    }

    /**
     * Leave the current group and reset local generation/memberId.
     */
    public void maybeLeaveGroup() {
        client.unschedule(heartbeatTask);
        if (!coordinatorUnknown() && generation > 0) {
            // this is a minimal effort attempt to leave the group. we do not
            // attempt any resending if the request fails or times out.
            sendLeaveGroupRequest();
        }

        this.generation = OffsetCommitRequest.DEFAULT_GENERATION_ID;
        this.memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID;
        rejoinNeeded = true;
    }

    private void sendLeaveGroupRequest() {
        LeaveGroupRequest request = new LeaveGroupRequest(groupId, memberId);
        RequestFuture<Void> future = client.send(coordinator, ApiKeys.LEAVE_GROUP, request)
                .compose(new LeaveGroupResponseHandler());

        future.addListener(new RequestFutureListener<Void>() {
            @Override
            public void onSuccess(Void value) {}

            @Override
            public void onFailure(RuntimeException e) {
                log.debug("LeaveGroup request for group {} failed with error", groupId, e);
            }
        });

        client.poll(future, 0);
    }

    private class LeaveGroupResponseHandler extends CoordinatorResponseHandler<LeaveGroupResponse, Void> {
        @Override
        public LeaveGroupResponse parse(ClientResponse response) {
            return new LeaveGroupResponse(response.responseBody());
        }

        @Override
        public void handle(LeaveGroupResponse leaveResponse, RequestFuture<Void> future) {
            // process the response
            short errorCode = leaveResponse.errorCode();
            if (errorCode == Errors.NONE.code())
                future.complete(null);
            else
                future.raise(Errors.forCode(errorCode));
        }
    }

    /**
     * Send a heartbeat request now (visible only for testing).
     */
    public RequestFuture<Void> sendHeartbeatRequest() {
        // 创建HeartbeatRequest
        HeartbeatRequest req = new HeartbeatRequest(this.groupId, this.generation, this.memberId);
        // 使用HeartbeatCompletionHandler对RequestFuture<ClientResponse>进行适配
        return client.send(coordinator, ApiKeys.HEARTBEAT, req)
                .compose(new HeartbeatCompletionHandler());
    }

    private class HeartbeatCompletionHandler extends CoordinatorResponseHandler<HeartbeatResponse, Void> {
        // 对client response进行解析，得到指定类型的响应
        @Override
        public HeartbeatResponse parse(ClientResponse response) {
            return new HeartbeatResponse(response.responseBody());
        }

        // 对解析后的响应进行处理
        @Override
        public void handle(HeartbeatResponse heartbeatResponse, RequestFuture<Void> future) {
            sensors.heartbeatLatency.record(response.requestLatencyMs());
            // 解析错误码
            Errors error = Errors.forCode(heartbeatResponse.errorCode());
            // 心跳正常
            if (error == Errors.NONE) {
                log.debug("Received successful heartbeat response for group {}", groupId);
                // 方法会触发监听器
                future.complete(null);
                // 找不到服务器对应的GroupCoordinator
            } else if (error == Errors.GROUP_COORDINATOR_NOT_AVAILABLE
                    || error == Errors.NOT_COORDINATOR_FOR_GROUP) {
                log.debug("Attempt to heart beat failed for group {} since coordinator {} is either not started or not valid.",
                        groupId, coordinator);
                // 清空unsent集合中对应的请求，并重新查找对应的GroupCoordinator
                coordinatorDead();
                // 方法会触发监听器
                future.raise(error);
            } else if (error == Errors.REBALANCE_IN_PROGRESS) {
                // 重新发送JoinGroupRequest信息
                log.debug("Attempt to heart beat failed for group {} since it is rebalancing.", groupId);
                AbstractCoordinator.this.rejoinNeeded = true;
                future.raise(Errors.REBALANCE_IN_PROGRESS);
            } else if (error == Errors.ILLEGAL_GENERATION) {
                // 重新发送JoinGroupRequest信息
                log.debug("Attempt to heart beat failed for group {} since generation id is not legal.", groupId);
                AbstractCoordinator.this.rejoinNeeded = true;
                future.raise(Errors.ILLEGAL_GENERATION);
            } else if (error == Errors.UNKNOWN_MEMBER_ID) {
                // 重新发送JoinGroupRequest信息
                log.debug("Attempt to heart beat failed for group {} since member id is not valid.", groupId);
                memberId = JoinGroupRequest.UNKNOWN_MEMBER_ID;
                AbstractCoordinator.this.rejoinNeeded = true;
                future.raise(Errors.UNKNOWN_MEMBER_ID);
            } else if (error == Errors.GROUP_AUTHORIZATION_FAILED) {
                future.raise(new GroupAuthorizationException(groupId));
            } else {
                future.raise(new KafkaException("Unexpected error in heartbeat response: " + error.message()));
            }
        }
    }

    protected abstract class CoordinatorResponseHandler<R, T>
            extends RequestFutureAdapter<ClientResponse, T> {
        protected ClientResponse response;

        // 对client response进行解析，得到指定类型的响应
        public abstract R parse(ClientResponse response);

        // 对解析后的响应进行处理
        public abstract void handle(R response, RequestFuture<T> future);

        @Override
        public void onFailure(RuntimeException e, RequestFuture<T> future) {
            // mark the coordinator as dead
            if (e instanceof DisconnectException)
                coordinatorDead();
            // 调用adapted对象的raise方法
            future.raise(e);
        }

        @Override
        public void onSuccess(ClientResponse clientResponse, RequestFuture<T> future) {
            try {
                this.response = clientResponse;
                // 解析client response
                R responseObj = parse(clientResponse);
                // 调用handle方法处理：子类实现
                handle(responseObj, future);
            } catch (RuntimeException e) {
                if (!future.isDone())
                    future.raise(e);
            }
        }

    }

    private class GroupCoordinatorMetrics {
        public final Metrics metrics;
        public final String metricGrpName;

        public final Sensor heartbeatLatency;
        public final Sensor joinLatency;
        public final Sensor syncLatency;

        public GroupCoordinatorMetrics(Metrics metrics, String metricGrpPrefix) {
            this.metrics = metrics;
            this.metricGrpName = metricGrpPrefix + "-coordinator-metrics";

            this.heartbeatLatency = metrics.sensor("heartbeat-latency");
            this.heartbeatLatency.add(metrics.metricName("heartbeat-response-time-max",
                this.metricGrpName,
                "The max time taken to receive a response to a heartbeat request"), new Max());
            this.heartbeatLatency.add(metrics.metricName("heartbeat-rate",
                this.metricGrpName,
                "The average number of heartbeats per second"), new Rate(new Count()));

            this.joinLatency = metrics.sensor("join-latency");
            this.joinLatency.add(metrics.metricName("join-time-avg",
                    this.metricGrpName,
                    "The average time taken for a group rejoin"), new Avg());
            this.joinLatency.add(metrics.metricName("join-time-max",
                    this.metricGrpName,
                    "The max time taken for a group rejoin"), new Avg());
            this.joinLatency.add(metrics.metricName("join-rate",
                    this.metricGrpName,
                    "The number of group joins per second"), new Rate(new Count()));

            this.syncLatency = metrics.sensor("sync-latency");
            this.syncLatency.add(metrics.metricName("sync-time-avg",
                    this.metricGrpName,
                    "The average time taken for a group sync"), new Avg());
            this.syncLatency.add(metrics.metricName("sync-time-max",
                    this.metricGrpName,
                    "The max time taken for a group sync"), new Avg());
            this.syncLatency.add(metrics.metricName("sync-rate",
                    this.metricGrpName,
                    "The number of group syncs per second"), new Rate(new Count()));

            Measurable lastHeartbeat =
                new Measurable() {
                    public double measure(MetricConfig config, long now) {
                        return TimeUnit.SECONDS.convert(now - heartbeat.lastHeartbeatSend(), TimeUnit.MILLISECONDS);
                    }
                };
            metrics.addMetric(metrics.metricName("last-heartbeat-seconds-ago",
                this.metricGrpName,
                "The number of seconds since the last controller heartbeat"),
                lastHeartbeat);
        }
    }

}
