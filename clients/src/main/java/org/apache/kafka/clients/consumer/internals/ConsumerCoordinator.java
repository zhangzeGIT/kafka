/**
 * Licensed to the Apache Software Foundation (ASF) under one or more contributor license agreements. See the NOTICE
 * file distributed with this work for additional information regarding copyright ownership. The ASF licenses this file
 * to You under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with the
 * License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.kafka.clients.consumer.internals;

import org.apache.kafka.clients.ClientResponse;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.consumer.CommitFailedException;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.OffsetCommitCallback;
import org.apache.kafka.clients.consumer.internals.PartitionAssignor.Assignment;
import org.apache.kafka.clients.consumer.internals.PartitionAssignor.Subscription;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.consumer.RetriableCommitFailedException;
import org.apache.kafka.common.errors.GroupAuthorizationException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.TopicAuthorizationException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.internals.TopicConstants;
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
import org.apache.kafka.common.requests.JoinGroupRequest.ProtocolMetadata;
import org.apache.kafka.common.requests.OffsetCommitRequest;
import org.apache.kafka.common.requests.OffsetCommitResponse;
import org.apache.kafka.common.requests.OffsetFetchRequest;
import org.apache.kafka.common.requests.OffsetFetchResponse;
import org.apache.kafka.common.utils.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * This class manages the coordination process with the consumer coordinator.
 */
public final class ConsumerCoordinator extends AbstractCoordinator {

    private static final Logger log = LoggerFactory.getLogger(ConsumerCoordinator.class);

    // PartitionAssignor列表，消费者发送的JoinGroupRequest请求中包含了消费者自身支持的PartitionAssignor信息
    // GroupCoordinator从所有消费者都支持的分配策略中选择一个
    // 通知leader使用此分配策略进行分区分配
    // 此字段的值通过Partition.assignment.strategy参数配置
    private final List<PartitionAssignor> assignors;
    // 元数据
    private final Metadata metadata;
    private final ConsumerCoordinatorMetrics sensors;
    // SubscriptionState
    private final SubscriptionState subscriptions;
    private final OffsetCommitCallback defaultOffsetCommitCallback;
    // 是否开启自动提交
    private final boolean autoCommitEnabled;
    // 自动提交offset
    private final AutoCommitTask autoCommitTask;
    // ConsumerInterceptor集合
    private final ConsumerInterceptors<?, ?> interceptors;
    // 是否排除内部topic
    private final boolean excludeInternalTopics;

    // 元数据快照信息
    // 构造方法中会添加一个监听器，当metadata更新时
    // AUTO_PATTERN：使用正则过滤Topic，得到需要订阅的Topic集合，设置到SubscriptionState的subscription集合和groupSubscription集合
    // AUTO_PATTERN或者AUTO_TOPICS:为当前metadata做一个快照，底层hashMap，记录每个topic中partition的个数，将
    //                              新旧快照进行比较，发送变换，表示topic发生分区数量变化
    //                              则将SubscriptionState的needsPartitionAssignment字段置为true，需要重新进行分区分配
    // metadataSnapshot记录新的快照
    private MetadataSnapshot metadataSnapshot;
    // 也是快照
    // 用来检测partition分配的过程中有没有发生分区数量变化
    // 具体是在Leader消费者开始分区分配操作前，使用此字段记录metadata快照
    // 收到SyncGroupResponse后，会比较此字段记录的快照与当前metadata是否发生变化
    // 如果发生变化，则要重新进行分区分配
    private MetadataSnapshot assignmentSnapshot;

    /**
     * Initialize the coordination manager.
     */
    public ConsumerCoordinator(ConsumerNetworkClient client,
                               String groupId,
                               int sessionTimeoutMs,
                               int heartbeatIntervalMs,
                               List<PartitionAssignor> assignors,
                               Metadata metadata,
                               SubscriptionState subscriptions,
                               Metrics metrics,
                               String metricGrpPrefix,
                               Time time,
                               long retryBackoffMs,
                               OffsetCommitCallback defaultOffsetCommitCallback,
                               boolean autoCommitEnabled,
                               long autoCommitIntervalMs,
                               ConsumerInterceptors<?, ?> interceptors,
                               boolean excludeInternalTopics) {
        super(client,
                groupId,
                sessionTimeoutMs,
                heartbeatIntervalMs,
                metrics,
                metricGrpPrefix,
                time,
                retryBackoffMs);
        this.metadata = metadata;

        this.metadata.requestUpdate();
        this.metadataSnapshot = new MetadataSnapshot(subscriptions, metadata.fetch());
        this.subscriptions = subscriptions;
        this.defaultOffsetCommitCallback = defaultOffsetCommitCallback;
        this.autoCommitEnabled = autoCommitEnabled;
        this.assignors = assignors;

        addMetadataListener();

        if (autoCommitEnabled) {
            this.autoCommitTask = new AutoCommitTask(autoCommitIntervalMs);
            this.autoCommitTask.reschedule();
        } else {
            this.autoCommitTask = null;
        }

        this.sensors = new ConsumerCoordinatorMetrics(metrics, metricGrpPrefix);
        this.interceptors = interceptors;
        this.excludeInternalTopics = excludeInternalTopics;
    }

    @Override
    public String protocolType() {
        return ConsumerProtocol.PROTOCOL_TYPE;
    }

    @Override
    public List<ProtocolMetadata> metadata() {
        List<ProtocolMetadata> metadataList = new ArrayList<>();
        for (PartitionAssignor assignor : assignors) {
            Subscription subscription = assignor.subscription(subscriptions.subscription());
            ByteBuffer metadata = ConsumerProtocol.serializeSubscription(subscription);
            metadataList.add(new ProtocolMetadata(assignor.name(), metadata));
        }
        return metadataList;
    }

    // 元数据更新时的监听器
    private void addMetadataListener() {
        this.metadata.addListener(new Metadata.Listener() {
            @Override
            public void onMetadataUpdate(Cluster cluster) {

                // AUTO_PATTERN模式
                if (subscriptions.hasPatternSubscription()) {

                    Set<String> unauthorizedTopics = new HashSet<String>();
                    // 权限验证
                    for (String topic : cluster.unauthorizedTopics()) {
                        if (filterTopic(topic))
                            unauthorizedTopics.add(topic);
                    }
                    if (!unauthorizedTopics.isEmpty())
                        throw new TopicAuthorizationException(unauthorizedTopics);

                    final List<String> topicsToSubscribe = new ArrayList<>();

                    for (String topic : cluster.topics())
                        // 通过subscriptionPattern匹配Topic
                        if (filterTopic(topic))
                            topicsToSubscribe.add(topic);

                    // 更新subscriptions集合，groupSubscription集合，assignment集合
                    subscriptions.changeSubscription(topicsToSubscribe);
                    metadata.setTopics(subscriptions.groupSubscription());
                } else if (!cluster.unauthorizedTopics().isEmpty()) {
                    throw new TopicAuthorizationException(new HashSet<>(cluster.unauthorizedTopics()));
                }

                // check if there are any changes to the metadata which should trigger a rebalance
                if (subscriptions.partitionsAutoAssigned()) {
                    MetadataSnapshot snapshot = new MetadataSnapshot(subscriptions, cluster);
                    if (!snapshot.equals(metadataSnapshot)) {
                        metadataSnapshot = snapshot;
                        subscriptions.needReassignment();
                    }
                }

            }
        });
    }

    private boolean filterTopic(String topic) {
        return subscriptions.getSubscribedPattern().matcher(topic).matches() &&
                !(excludeInternalTopics && TopicConstants.INTERNAL_TOPICS.contains(topic));
    }

    private PartitionAssignor lookupAssignor(String name) {
        for (PartitionAssignor assignor : this.assignors) {
            if (assignor.name().equals(name))
                return assignor;
        }
        return null;
    }

    @Override
    protected void onJoinComplete(int generation,
                                  String memberId,
                                  String assignmentStrategy,
                                  ByteBuffer assignmentBuffer) {
        // if we were the assignor, then we need to make sure that there have been no metadata updates
        // since the rebalance begin. Otherwise, we won't rebalance again until the next metadata change

        // 分区之前的快照与最新的快照比，不一致，则表示分区分配过程中出现了Topic增删或分区数量的变化
        // 将needsPartitionAssignment设置为true，需要进行重新分区
        if (assignmentSnapshot != null && !assignmentSnapshot.equals(metadataSnapshot)) {
            subscriptions.needReassignment();
            return;
        }

        PartitionAssignor assignor = lookupAssignor(assignmentStrategy);
        if (assignor == null)
            throw new IllegalStateException("Coordinator selected invalid assignment protocol: " + assignmentStrategy);

        // 反序列化，更新assignment
        Assignment assignment = ConsumerProtocol.deserializeAssignment(assignmentBuffer);

        // set the flag to refresh last committed offsets
        subscriptions.needRefreshCommits();

        // update partition assignment
        subscriptions.assignFromSubscribed(assignment.partitions());

        // give the assignor a chance to update internal state based on the received assignment

        // 回调函数
        assignor.onAssignment(assignment);

        // reschedule the auto commit starting from now

        // 如果开启了自动提交offset功能，则重新启动AutoCommitTask定时任务
        if (autoCommitEnabled)
            autoCommitTask.reschedule();

        // execute the user's callback after rebalance
        ConsumerRebalanceListener listener = subscriptions.listener();
        log.info("Setting newly assigned partitions {} for group {}", subscriptions.assignedPartitions(), groupId);
        try {
            Set<TopicPartition> assigned = new HashSet<>(subscriptions.assignedPartitions());
            // 回调ConsumerRebalanceListener
            listener.onPartitionsAssigned(assigned);
        } catch (WakeupException e) {
            throw e;
        } catch (Exception e) {
            log.error("User provided listener {} for group {} failed on partition assignment",
                    listener.getClass().getName(), groupId, e);
        }
    }

    @Override
    protected Map<String, ByteBuffer> performAssignment(String leaderId,
                                                        String assignmentStrategy,
                                                        Map<String, ByteBuffer> allSubscriptions) {
        // 步骤三：查找分区分配使用的PartitionAssignor
        PartitionAssignor assignor = lookupAssignor(assignmentStrategy);
        if (assignor == null)
            throw new IllegalStateException("Coordinator selected invalid assignment protocol: " + assignmentStrategy);

        // 反序列化及汇总操作
        Set<String> allSubscribedTopics = new HashSet<>();
        Map<String, Subscription> subscriptions = new HashMap<>();
        for (Map.Entry<String, ByteBuffer> subscriptionEntry : allSubscriptions.entrySet()) {
            Subscription subscription = ConsumerProtocol.deserializeSubscription(subscriptionEntry.getValue());
            subscriptions.put(subscriptionEntry.getKey(), subscription);
            allSubscribedTopics.addAll(subscription.topics());
        }

        // the leader will begin watching for changes to any of the topics the group is interested in,
        // which ensures that all metadata changes will eventually be seen

        // 步骤四：对于leader来说，关注consumer Group中全部消费者订阅的topic
        this.subscriptions.groupSubscribe(allSubscribedTopics);
        metadata.setTopics(this.subscriptions.groupSubscription());

        // update metadata (if needed) and keep track of the metadata used for assignment so that
        // we can check after rebalance completion whether anything has changed

        // 步骤五：更新metadata
        client.ensureFreshMetadata();
        // 步骤六：记录快照
        assignmentSnapshot = metadataSnapshot;

        log.debug("Performing assignment for group {} using strategy {} with subscriptions {}",
                groupId, assignor.name(), subscriptions);

        // 步骤七：进行分区分配
        Map<String, Assignment> assignment = assignor.assign(metadata.fetch(), subscriptions);

        log.debug("Finished assignment for group {}: {}", groupId, assignment);

        // 步骤八：将分区分配结果序列化，并保存到groupAssignment中
        Map<String, ByteBuffer> groupAssignment = new HashMap<>();
        for (Map.Entry<String, Assignment> assignmentEntry : assignment.entrySet()) {
            // 序列化操作
            ByteBuffer buffer = ConsumerProtocol.serializeAssignment(assignmentEntry.getValue());
            groupAssignment.put(assignmentEntry.getKey(), buffer);
        }

        return groupAssignment;
    }

    @Override
    protected void onJoinPrepare(int generation, String memberId) {
        // commit offsets prior to rebalance if auto-commit enabled
        // 进行一次同步提交offset操作
        maybeAutoCommitOffsetsSync();

        // execute the user's callback before rebalance

        // 调用subscriptionState中设置ConsumerRebalanceListener
        ConsumerRebalanceListener listener = subscriptions.listener();
        log.info("Revoking previously assigned partitions {} for group {}", subscriptions.assignedPartitions(), groupId);
        try {
            Set<TopicPartition> revoked = new HashSet<>(subscriptions.assignedPartitions());
            listener.onPartitionsRevoked(revoked);
        } catch (WakeupException e) {
            throw e;
        } catch (Exception e) {
            log.error("User provided listener {} for group {} failed on partition revocation",
                    listener.getClass().getName(), groupId, e);
        }

        assignmentSnapshot = null;
        // 将needsPartitionAssignment设置为true
        subscriptions.needReassignment();
    }

    @Override
    public boolean needRejoin() {
        // 检测subscriptionType
        return subscriptions.partitionsAutoAssigned() &&
                // 检测needRejoin的值，检测needsPartitionAssignment
                (super.needRejoin() || subscriptions.partitionAssignmentNeeded());
    }

    /**
     * Refresh the committed offsets for provided partitions.
     * 发送OffsetFetchRequest请求，更新subscriptions
     */
    public void refreshCommittedOffsetsIfNeeded() {
        // 检查needsFetchCommittedOffsets
        if (subscriptions.refreshCommitsNeeded()) {
            // 发送OffsetFetchRequest并处理OffsetFetchResponse响应
            // 返回最近提交的offset集合
            Map<TopicPartition, OffsetAndMetadata> offsets = fetchCommittedOffsets(subscriptions.assignedPartitions());
            for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : offsets.entrySet()) {
                TopicPartition tp = entry.getKey();
                // verify assignment is still active
                // 处理offsets集合，更新对应的TopicPartitionState的Committed字段
                if (subscriptions.isAssigned(tp))
                    this.subscriptions.committed(tp, entry.getValue());
            }
            // 将needsFetchCommittedOffsets设置为false
            // offsetFetch Request处理结束
            this.subscriptions.commitsRefreshed();
        }
    }

    /**
     * Fetch the current committed offsets from the coordinator for a set of partitions.
     * @param partitions The partitions to fetch offsets for
     * @return A map from partition to the committed offset
     */
    public Map<TopicPartition, OffsetAndMetadata> fetchCommittedOffsets(Set<TopicPartition> partitions) {
        while (true) {
            // 检测GroupCoordinator状态
            ensureCoordinatorReady();

            // contact coordinator to fetch committed offsets

            // 创建并缓存OffsetFetchRequest请求
            RequestFuture<Map<TopicPartition, OffsetAndMetadata>> future = sendOffsetFetchRequest(partitions);
            // 阻塞发送OffsetFetchRequest请求
            client.poll(future);

            if (future.succeeded())
                // 返回从服务端得到的offset
                return future.value();

            // 如果是RetriableException，则退避一段时间，重试
            if (!future.isRetriable())
                throw future.exception();

            time.sleep(retryBackoffMs);
        }
    }

    /**
     * Ensure that we have a valid partition assignment from the coordinator.
     */
    public void ensurePartitionAssignment() {
        // 步骤一：检测订阅类型
        if (subscriptions.partitionsAutoAssigned()) {
            // Due to a race condition between the initial metadata fetch and the initial rebalance, we need to ensure that
            // the metadata is fresh before joining initially, and then request the metadata update. If metadata update arrives
            // while the rebalance is still pending (for example, when the join group is still inflight), then we will lose
            // track of the fact that we need to rebalance again to reflect the change to the topic subscription. Without
            // ensuring that the metadata is fresh, any metadata update that changes the topic subscriptions and arrives with a
            // rebalance in progress will essentially be ignored. See KAFKA-3949 for the complete description of the problem.
            if (subscriptions.hasPatternSubscription())
                // 检测是否需要更新metadata
                client.ensureFreshMetadata();

            ensureActiveGroup();
        }
    }

    @Override
    public void close() {
        // we do not need to re-enable wakeups since we are closing already
        client.disableWakeups();
        try {
            maybeAutoCommitOffsetsSync();
        } finally {
            super.close();
        }
    }


    public void commitOffsetsAsync(final Map<TopicPartition, OffsetAndMetadata> offsets, OffsetCommitCallback callback) {
        // 将needsFetchCommittedOffsets设置为true
        this.subscriptions.needRefreshCommits();
        // 创建并缓存OffsetCommitRequest请求
        RequestFuture<Void> future = sendOffsetCommitRequest(offsets);
        // 选择回调函数
        final OffsetCommitCallback cb = callback == null ? defaultOffsetCommitCallback : callback;
        future.addListener(new RequestFutureListener<Void>() {
            @Override
            public void onSuccess(Void value) {
                if (interceptors != null)
                    interceptors.onCommit(offsets);
                // 调用回调函数
                cb.onComplete(offsets, null);
            }

            @Override
            public void onFailure(RuntimeException e) {
                if (e instanceof RetriableException) {
                    cb.onComplete(offsets, new RetriableCommitFailedException("Commit offsets failed with retriable exception. You should retry committing offsets.", e));
                } else {
                    cb.onComplete(offsets, e);
                }
            }
        });

        // ensure the commit has a chance to be transmitted (without blocking on its completion).
        // Note that commits are treated as heartbeats by the coordinator, so there is no need to
        // explicitly allow heartbeats through delayed task execution.

        // 发送出去
        client.pollNoWakeup();
    }

    /**
     * Commit offsets synchronously. This method will retry until the commit completes successfully
     * or an unrecoverable error is encountered.
     * @param offsets The offsets to be committed
     * @throws org.apache.kafka.common.errors.AuthorizationException if the consumer is not authorized to the group
     *             or to any of the specified partitions
     * @throws CommitFailedException if an unrecoverable error occurs before the commit can be completed
     */
    public void commitOffsetsSync(Map<TopicPartition, OffsetAndMetadata> offsets) {
        if (offsets.isEmpty())
            return;

        while (true) {
            ensureCoordinatorReady();

            RequestFuture<Void> future = sendOffsetCommitRequest(offsets);
            client.poll(future);

            if (future.succeeded()) {
                if (interceptors != null)
                    interceptors.onCommit(offsets);
                return;
            }

            if (!future.isRetriable())
                throw future.exception();

            time.sleep(retryBackoffMs);
        }
    }

    private class AutoCommitTask implements DelayedTask {
        private final long interval;

        public AutoCommitTask(long interval) {
            this.interval = interval;
        }

        private void reschedule() {
            client.schedule(this, time.milliseconds() + interval);
        }

        private void reschedule(long at) {
            client.schedule(this, at);
        }

        public void run(final long now) {
            if (coordinatorUnknown()) {
                log.debug("Cannot auto-commit offsets for group {} since the coordinator is unknown", groupId);
                reschedule(now + retryBackoffMs);
                return;
            }

            if (needRejoin()) {
                // skip the commit when we're rejoining since we'll commit offsets synchronously
                // before the revocation callback is invoked
                reschedule(now + interval);
                return;
            }

            commitOffsetsAsync(subscriptions.allConsumed(), new OffsetCommitCallback() {
                @Override
                public void onComplete(Map<TopicPartition, OffsetAndMetadata> offsets, Exception exception) {
                    if (exception == null) {
                        reschedule(now + interval);
                    } else {
                        log.warn("Auto offset commit failed for group {}: {}", groupId, exception.getMessage());
                        reschedule(now + interval);
                    }
                }
            });
        }
    }

    private void maybeAutoCommitOffsetsSync() {
        if (autoCommitEnabled) {
            try {
                commitOffsetsSync(subscriptions.allConsumed());
            } catch (WakeupException e) {
                // rethrow wakeups since they are triggered by the user
                throw e;
            } catch (Exception e) {
                // consistent with async auto-commit failures, we do not propagate the exception
                log.warn("Auto offset commit failed for group {}: {}", groupId, e.getMessage());
            }
        }
    }

    /**
     * Commit offsets for the specified list of topics and partitions. This is a non-blocking call
     * which returns a request future that can be polled in the case of a synchronous commit or ignored in the
     * asynchronous case.
     *
     * @param offsets The list of offsets per partition that should be committed.
     * @return A request future whose value indicates whether the commit was successful or not
     */
    private RequestFuture<Void> sendOffsetCommitRequest(final Map<TopicPartition, OffsetAndMetadata> offsets) {
        if (coordinatorUnknown())
            return RequestFuture.coordinatorNotAvailable();

        if (offsets.isEmpty())
            return RequestFuture.voidSuccess();

        // create the offset commit request
        Map<TopicPartition, OffsetCommitRequest.PartitionData> offsetData = new HashMap<>(offsets.size());
        for (Map.Entry<TopicPartition, OffsetAndMetadata> entry : offsets.entrySet()) {
            OffsetAndMetadata offsetAndMetadata = entry.getValue();
            offsetData.put(entry.getKey(), new OffsetCommitRequest.PartitionData(
                    offsetAndMetadata.offset(), offsetAndMetadata.metadata()));
        }

        OffsetCommitRequest req = new OffsetCommitRequest(this.groupId,
                this.generation,
                this.memberId,
                OffsetCommitRequest.DEFAULT_RETENTION_TIME,
                offsetData);

        log.trace("Sending offset-commit request with {} to coordinator {} for group {}", offsets, coordinator, groupId);

        return client.send(coordinator, ApiKeys.OFFSET_COMMIT, req)
                .compose(new OffsetCommitResponseHandler(offsets));
    }

    public static class DefaultOffsetCommitCallback implements OffsetCommitCallback {
        @Override
        public void onComplete(Map<TopicPartition, OffsetAndMetadata> offsets, Exception exception) {
            if (exception != null)
                log.error("Offset commit failed.", exception);
        }
    }

    /**
     * 处理OffsetCommitResponse
     */
    private class OffsetCommitResponseHandler extends CoordinatorResponseHandler<OffsetCommitResponse, Void> {

        private final Map<TopicPartition, OffsetAndMetadata> offsets;

        public OffsetCommitResponseHandler(Map<TopicPartition, OffsetAndMetadata> offsets) {
            this.offsets = offsets;
        }

        @Override
        public OffsetCommitResponse parse(ClientResponse response) {
            return new OffsetCommitResponse(response.responseBody());
        }

        @Override
        public void handle(OffsetCommitResponse commitResponse, RequestFuture<Void> future) {
            // 初始化操作
            sensors.commitLatency.record(response.requestLatencyMs());
            Set<String> unauthorizedTopics = new HashSet<>();

            // 遍历待提交的所欲offset信息
            for (Map.Entry<TopicPartition, Short> entry : commitResponse.responseData().entrySet()) {
                TopicPartition tp = entry.getKey();
                OffsetAndMetadata offsetAndMetadata = this.offsets.get(tp);
                long offset = offsetAndMetadata.offset();

                // 获取错误码
                Errors error = Errors.forCode(entry.getValue());
                if (error == Errors.NONE) {
                    log.debug("Group {} committed offset {} for partition {}", groupId, offset, tp);
                    if (subscriptions.isAssigned(tp))
                        // update the local cache only if the partition is still assigned
                        // 更新SubscriptionState中对应的TopicPartitionState的committed字段
                        subscriptions.committed(tp, offsetAndMetadata);
                } else if (error == Errors.GROUP_AUTHORIZATION_FAILED) {
                    log.error("Not authorized to commit offsets for group {}", groupId);
                    future.raise(new GroupAuthorizationException(groupId));
                    return;
                } else if (error == Errors.TOPIC_AUTHORIZATION_FAILED) {
                    unauthorizedTopics.add(tp.topic());
                } else if (error == Errors.OFFSET_METADATA_TOO_LARGE
                        || error == Errors.INVALID_COMMIT_OFFSET_SIZE) {
                    // raise the error to the user
                    log.debug("Offset commit for group {} failed on partition {}: {}", groupId, tp, error.message());
                    future.raise(error);
                    return;
                } else if (error == Errors.GROUP_LOAD_IN_PROGRESS) {
                    // just retry
                    log.debug("Offset commit for group {} failed: {}", groupId, error.message());
                    future.raise(error);
                    return;
                } else if (error == Errors.GROUP_COORDINATOR_NOT_AVAILABLE
                        || error == Errors.NOT_COORDINATOR_FOR_GROUP
                        || error == Errors.REQUEST_TIMED_OUT) {
                    log.debug("Offset commit for group {} failed: {}", groupId, error.message());
                    coordinatorDead();
                    future.raise(error);
                    return;
                } else if (error == Errors.UNKNOWN_MEMBER_ID
                        || error == Errors.ILLEGAL_GENERATION
                        || error == Errors.REBALANCE_IN_PROGRESS) {
                    // need to re-join group
                    log.debug("Offset commit for group {} failed: {}", groupId, error.message());
                    subscriptions.needReassignment();
                    future.raise(new CommitFailedException("Commit cannot be completed since the group has already " +
                            "rebalanced and assigned the partitions to another member. This means that the time " +
                            "between subsequent calls to poll() was longer than the configured session.timeout.ms, " +
                            "which typically implies that the poll loop is spending too much time message processing. " +
                            "You can address this either by increasing the session timeout or by reducing the maximum " +
                            "size of batches returned in poll() with max.poll.records."));
                    return;
                } else {
                    log.error("Group {} failed to commit partition {} at offset {}: {}", groupId, tp, offset, error.message());
                    future.raise(new KafkaException("Unexpected error in commit: " + error.message()));
                    return;
                }
            }

            if (!unauthorizedTopics.isEmpty()) {
                log.error("Not authorized to commit to topics {} for group {}", unauthorizedTopics, groupId);
                future.raise(new TopicAuthorizationException(unauthorizedTopics));
            } else {
                // 传播成功提交offset的事件，最终调用上面添加的Callback
                future.complete(null);
            }
        }
    }

    /**
     * Fetch the committed offsets for a set of partitions. This is a non-blocking call. The
     * returned future can be polled to get the actual offsets returned from the broker.
     *
     * @param partitions The set of partitions to get offsets for.
     * @return A request future containing the committed offsets.
     */
    private RequestFuture<Map<TopicPartition, OffsetAndMetadata>> sendOffsetFetchRequest(Set<TopicPartition> partitions) {
        if (coordinatorUnknown())
            return RequestFuture.coordinatorNotAvailable();

        log.debug("Group {} fetching committed offsets for partitions: {}", groupId, partitions);
        // construct the request
        OffsetFetchRequest request = new OffsetFetchRequest(this.groupId, new ArrayList<>(partitions));

        // send the request with a callback
        return client.send(coordinator, ApiKeys.OFFSET_FETCH, request)
                .compose(new OffsetFetchResponseHandler());
    }

    // 处理OffsetFetchResponse
    private class OffsetFetchResponseHandler extends CoordinatorResponseHandler<OffsetFetchResponse, Map<TopicPartition, OffsetAndMetadata>> {

        @Override
        public OffsetFetchResponse parse(ClientResponse response) {
            return new OffsetFetchResponse(response.responseBody());
        }

        @Override
        public void handle(OffsetFetchResponse response, RequestFuture<Map<TopicPartition, OffsetAndMetadata>> future) {
            // 记录从服务器获取的offset集合
            Map<TopicPartition, OffsetAndMetadata> offsets = new HashMap<>(response.responseData().size());
            for (Map.Entry<TopicPartition, OffsetFetchResponse.PartitionData> entry : response.responseData().entrySet()) {
                // 处理OffsetFetchResponse中的每个分区
                TopicPartition tp = entry.getKey();
                OffsetFetchResponse.PartitionData data = entry.getValue();
                // 异常
                if (data.hasError()) {
                    Errors error = Errors.forCode(data.errorCode);
                    log.debug("Group {} failed to fetch offset for partition {}: {}", groupId, tp, error.message());

                    if (error == Errors.GROUP_LOAD_IN_PROGRESS) {
                        // just retry
                        future.raise(error);
                    } else if (error == Errors.NOT_COORDINATOR_FOR_GROUP) {
                        // re-discover the coordinator and retry
                        coordinatorDead();
                        future.raise(error);
                    } else if (error == Errors.UNKNOWN_MEMBER_ID
                            || error == Errors.ILLEGAL_GENERATION) {
                        // need to re-join group
                        subscriptions.needReassignment();
                        future.raise(error);
                    } else {
                        future.raise(new KafkaException("Unexpected error in fetch offset response: " + error.message()));
                    }
                    return;
                } else if (data.offset >= 0) {
                    // 记录正常的offset
                    // record the position with the offset (-1 indicates no committed offset to fetch)
                    offsets.put(tp, new OffsetAndMetadata(data.offset, data.metadata));
                } else {
                    log.debug("Group {} has no committed offset for partition {}", groupId, tp);
                }
            }

            // 传播offset集合，最终通过fetchCommittedOffsets方法返回
            future.complete(offsets);
        }
    }

    private class ConsumerCoordinatorMetrics {
        public final Metrics metrics;
        public final String metricGrpName;

        public final Sensor commitLatency;

        public ConsumerCoordinatorMetrics(Metrics metrics, String metricGrpPrefix) {
            this.metrics = metrics;
            this.metricGrpName = metricGrpPrefix + "-coordinator-metrics";

            this.commitLatency = metrics.sensor("commit-latency");
            this.commitLatency.add(metrics.metricName("commit-latency-avg",
                this.metricGrpName,
                "The average time taken for a commit request"), new Avg());
            this.commitLatency.add(metrics.metricName("commit-latency-max",
                this.metricGrpName,
                "The max time taken for a commit request"), new Max());
            this.commitLatency.add(metrics.metricName("commit-rate",
                this.metricGrpName,
                "The number of commit calls per second"), new Rate(new Count()));

            Measurable numParts =
                new Measurable() {
                    public double measure(MetricConfig config, long now) {
                        return subscriptions.assignedPartitions().size();
                    }
                };
            metrics.addMetric(metrics.metricName("assigned-partitions",
                this.metricGrpName,
                "The number of partitions currently assigned to this consumer"), numParts);
        }
    }

    private static class MetadataSnapshot {
        private final Map<String, Integer> partitionsPerTopic;

        public MetadataSnapshot(SubscriptionState subscription, Cluster cluster) {
            Map<String, Integer> partitionsPerTopic = new HashMap<>();
            for (String topic : subscription.groupSubscription())
                partitionsPerTopic.put(topic, cluster.partitionCountForTopic(topic));
            this.partitionsPerTopic = partitionsPerTopic;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            MetadataSnapshot that = (MetadataSnapshot) o;
            return partitionsPerTopic != null ? partitionsPerTopic.equals(that.partitionsPerTopic) : that.partitionsPerTopic == null;
        }

        @Override
        public int hashCode() {
            return partitionsPerTopic != null ? partitionsPerTopic.hashCode() : 0;
        }
    }


}
