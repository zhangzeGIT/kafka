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

import org.apache.kafka.clients.ClientRequest;
import org.apache.kafka.clients.ClientResponse;
import org.apache.kafka.clients.KafkaClient;
import org.apache.kafka.clients.Metadata;
import org.apache.kafka.clients.RequestCompletionHandler;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.requests.AbstractRequest;
import org.apache.kafka.common.requests.RequestHeader;
import org.apache.kafka.common.requests.RequestSend;
import org.apache.kafka.common.utils.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Higher level consumer access to the network layer with basic support for futures and
 * task scheduling. This class is not thread-safe, except for wakeup().
 * 对NetworkClient进行了封装
 */
public class ConsumerNetworkClient implements Closeable {
    private static final Logger log = LoggerFactory.getLogger(ConsumerNetworkClient.class);

    // NetWorkClient 对象
    private final KafkaClient client;
    // 由调用KafkaConsumer对象的消费者线程之外的其他线程设置，表示要中断KafkaConsumer线程
    private final AtomicBoolean wakeup = new AtomicBoolean(false);
    // 定时任务队列，底层是JDK的PriorityQueue
    private final DelayedTaskQueue delayedTasks = new DelayedTaskQueue();
    // 缓冲队列，key是node节点，value是发往此node的ClientRequest集合
    private final Map<Node, List<ClientRequest>> unsent = new HashMap<>();
    // 元数据
    private final Metadata metadata;
    private final Time time;
    private final long retryBackoffMs;
    // ClientRequest在unsent中缓存的超时时间
    private final long unsentExpiryMs;

    // this count is only accessed from the consumer's main thread
    // KafkaConsumer是否在执行不可中断的方法，进入不可中断方法+1，退出-1
    private int wakeupDisabledCount = 0;


    public ConsumerNetworkClient(KafkaClient client,
                                 Metadata metadata,
                                 Time time,
                                 long retryBackoffMs,
                                 long requestTimeoutMs) {
        this.client = client;
        this.metadata = metadata;
        this.time = time;
        this.retryBackoffMs = retryBackoffMs;
        this.unsentExpiryMs = requestTimeoutMs;
    }

    /**
     * Schedule a new task to be executed at the given time. This is "best-effort" scheduling and
     * should only be used for coarse synchronization.
     * @param task The task to be scheduled
     * @param at The time it should run
     */
    // 向delayedTasks队列中添加定时任务
    public void schedule(DelayedTask task, long at) {
        delayedTasks.add(task, at);
    }

    /**
     * Unschedule a task. This will remove all instances of the task from the task queue.
     * This is a no-op if the task is not scheduled.
     * @param task The task to be unscheduled.
     */
    public void unschedule(DelayedTask task) {
        delayedTasks.remove(task);
    }

    /**
     * Send a new request. Note that the request is not actually transmitted on the
     * network until one of the {@link #poll(long)} variants is invoked. At this
     * point the request will either be transmitted successfully or will fail.
     * Use the returned future to obtain the result of the send. Note that there is no
     * need to check for disconnects explicitly on the {@link ClientResponse} object;
     * instead, the future will be failed with a {@link DisconnectException}.
     * @param node The destination of the request
     * @param api The Kafka API call
     * @param request The request payload
     * @return A future which indicates the result of the send.
     */
    // 会将待发送的请求封装成ClientRequest，然后保存到unsent集合中等待发送
    public RequestFuture<ClientResponse> send(Node node,
                                              ApiKeys api,
                                              AbstractRequest request) {
        long now = time.milliseconds();
        RequestFutureCompletionHandler future = new RequestFutureCompletionHandler();
        RequestHeader header = client.nextRequestHeader(api);
        RequestSend send = new RequestSend(node.idString(), header, request.toStruct());
        // 创建ClientRequest对象，并保存到unsent集合中
        put(node, new ClientRequest(now, true, send, future));
        return future;
    }

    // 向unsent中添加请求
    private void put(Node node, ClientRequest request) {
        List<ClientRequest> nodeUnsent = unsent.get(node);
        if (nodeUnsent == null) {
            nodeUnsent = new ArrayList<>();
            unsent.put(node, nodeUnsent);
        }
        nodeUnsent.add(request);
    }

    // 查找Kafka集群负载最低的Node
    public Node leastLoadedNode() {
        return client.leastLoadedNode(time.milliseconds());
    }

    /**
     * Block until the metadata has been refreshed.
     */
    // 循环调用poll方法，知道Metadata版本号增加，实现阻塞等待Metadata更新完成
    public void awaitMetadataUpdate() {
        int version = this.metadata.requestUpdate();
        do {
            poll(Long.MAX_VALUE);
        } while (this.metadata.version() == version);
    }

    /**
     * Ensure our metadata is fresh (if an update is expected, this will block
     * until it has completed).
     */
    public void ensureFreshMetadata() {
        // 如果长时间没有更新或Metadata.needUpdate字段为true，则更新metadata
        // 更新原因是：防止因使用过期的Metadata进行Rebalance操作而导致多次Rebalance
        if (this.metadata.updateRequested() || this.metadata.timeToNextUpdate(time.milliseconds()) == 0)
            awaitMetadataUpdate();
    }

    /**
     * Wakeup an active poll. This will cause the polling thread to throw an exception either
     * on the current poll if one is active, or the next poll.
     */
    public void wakeup() {
        this.wakeup.set(true);
        this.client.wakeup();
    }

    /**
     * Block indefinitely until the given request future has finished.
     * @param future The request future to await.
     * @throws WakeupException if {@link #wakeup()} is called from another thread
     */
    // 阻塞发送请求的动能
    public void poll(RequestFuture<?> future) {
        // 循环检测future，即请求完成情况
        while (!future.isDone())
            // 请求未完成，则调用poll方法
            poll(Long.MAX_VALUE);
    }

    /**
     * Block until the provided request future request has finished or the timeout has expired.
     * @param future The request future to wait for
     * @param timeout The maximum duration (in ms) to wait for the request
     * @return true if the future is done, false otherwise
     * @throws WakeupException if {@link #wakeup()} is called from another thread
     */
    public boolean poll(RequestFuture<?> future, long timeout) {
        long begin = time.milliseconds();
        long remaining = timeout;
        long now = begin;
        do {
            poll(remaining, now, true);
            now = time.milliseconds();
            long elapsed = now - begin;
            remaining = timeout - elapsed;
        } while (!future.isDone() && remaining > 0);
        return future.isDone();
    }

    /**
     * Poll for any network IO.
     * @param timeout The maximum time to wait for an IO event.
     * @throws WakeupException if {@link #wakeup()} is called from another thread
     */
    public void poll(long timeout) {
        poll(timeout, time.milliseconds(), true);
    }

    /**
     * Poll for any network IO.
     * @param timeout timeout in milliseconds
     * @param now current time in milliseconds
     */
    public void poll(long timeout, long now) {
        poll(timeout, now, true);
    }

    /**
     * Poll for network IO and return immediately. This will not trigger wakeups,
     * nor will it execute any delayed tasks.
     */
    // 不可被中断的poll
    public void pollNoWakeup() {
        // 将wakeupDisabledCount加一
        disableWakeups();
        try {
            poll(0, time.milliseconds(), false);
        } finally {
            enableWakeups();
        }
    }

    /**
     * 核心方法
     * @param timeout poll方法最长阻塞时间（ms）
     * @param now 当前时间戳
     * @param executeDelayedTasks 表示是否执行delayedTasks队列中的定时任务
     */
    private void poll(long timeout, long now, boolean executeDelayedTasks) {
        // send all the requests we can send now
        // 循环处理unsent中缓存的请求
        // 步骤一：检测发送条件，将请求放入KafkaChannel.send字段，待发送
        trySend(now);

        // ensure we don't poll any longer than the deadline for
        // the next scheduled task

        // 步骤二：计算超时时间
        timeout = Math.min(timeout, delayedTasks.nextTimeout(now));
        // 步骤三四：调用NetworkClient.poll方法，并检测是否有中断请求
        clientPoll(timeout, now);
        // 步骤五：重置当前时间
        now = time.milliseconds();

        // handle any disconnects by failing the active requests. note that disconnects must
        // be checked immediately following poll since any subsequent call to client.ready()
        // will reset the disconnect status
        // 步骤六：根据连接状态，处理unsent中的请求
        checkDisconnects(now);

        // execute scheduled tasks
        // 步骤七：处理定时任务
        if (executeDelayedTasks)
            delayedTasks.poll(now);

        // try again to send requests since buffer space may have been
        // cleared or a connect finished in the poll
        // 步骤八：步骤三中调用NetworkClient.poll方法，在其中可能已经将KafkaChannel.send字段上的请求发送出去了
        // 也可能已经新建了某些Node的网络连接，所以在此尝试trySend
        trySend(now);

        // fail requests that couldn't be sent if they have expired

        // 步骤九：处理unsent中超时任务
        failExpiredRequests(now);
    }

    /**
     * Execute delayed tasks now.
     * 是否处理delayedTasks队列中超时的定时任务
     * @param now current time in milliseconds
     * @throws WakeupException if a wakeup has been requested
     */
    public void executeDelayedTasks(long now) {
        delayedTasks.poll(now);
        maybeTriggerWakeup();
    }

    /**
     * Block until all pending requests from the given node have finished.
     * @param node The node to await requests from
     */
    // 等待unsent和InFightRequests中的请求全部完成
    public void awaitPendingRequests(Node node) {
        while (pendingRequestCount(node) > 0)
            poll(retryBackoffMs);
    }

    /**
     * Get the count of pending requests to the given node. This includes both request that
     * have been transmitted (i.e. in-flight requests) and those which are awaiting transmission.
     * @param node The node in question
     * @return The number of pending requests
     */
    public int pendingRequestCount(Node node) {
        List<ClientRequest> pending = unsent.get(node);
        int unsentCount = pending == null ? 0 : pending.size();
        return unsentCount + client.inFlightRequestCount(node.idString());
    }

    /**
     * Get the total count of pending requests from all nodes. This includes both requests that
     * have been transmitted (i.e. in-flight requests) and those which are awaiting transmission.
     * @return The total count of pending requests
     */
    public int pendingRequestCount() {
        int total = 0;
        for (List<ClientRequest> requests: unsent.values())
            total += requests.size();
        return total + client.inFlightRequestCount();
    }

    // 检查连接状态，消费者与每个node之间的链接状态
    // 检测到连接断开的node时，会将其在unsent集合中对应的全部ClientRequest对象清除掉
    // 之后调用这些ClientRequest的回调函数
    private void checkDisconnects(long now) {
        // any disconnects affecting requests that have already been transmitted will be handled
        // by NetworkClient, so we just need to check whether connections for any of the unsent
        // requests have been disconnected; if they have, then we complete the corresponding future
        // and set the disconnect flag in the ClientResponse
        Iterator<Map.Entry<Node, List<ClientRequest>>> iterator = unsent.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Node, List<ClientRequest>> requestEntry = iterator.next();
            // 遍历集合中的node
            Node node = requestEntry.getKey();
            // 检测消费者与每个node之间的连接状态
            if (client.connectionFailed(node)) {
                // Remove entry before invoking request callback to avoid callbacks handling
                // coordinator failures traversing the unsent list again.
                // 从unsent集合中删除此node对应的全部ClientRequest
                iterator.remove();
                for (ClientRequest request : requestEntry.getValue()) {
                    RequestFutureCompletionHandler handler =
                            (RequestFutureCompletionHandler) request.callback();
                    // 调用ClientRequest的回调函数
                    handler.onComplete(new ClientResponse(request, now, true, null));
                }
            }
        }
    }

    // 处理unsent中超时请求，遍历整个unsent集合，检测每个ClientRequest是否超时
    // 调用超时ClientRequest的回调函数，并将其从unsent集合中删除
    private void failExpiredRequests(long now) {
        // clear all expired unsent requests and fail their corresponding futures
        Iterator<Map.Entry<Node, List<ClientRequest>>> iterator = unsent.entrySet().iterator();
        // 遍历unsent集合
        while (iterator.hasNext()) {
            Map.Entry<Node, List<ClientRequest>> requestEntry = iterator.next();
            Iterator<ClientRequest> requestIterator = requestEntry.getValue().iterator();
            while (requestIterator.hasNext()) {
                ClientRequest request = requestIterator.next();
                // 检测是否超时
                if (request.createdTimeMs() < now - unsentExpiryMs) {
                    RequestFutureCompletionHandler handler =
                            (RequestFutureCompletionHandler) request.callback();
                    // 调用回调函数
                    handler.raise(new TimeoutException("Failed to send request after " + unsentExpiryMs + " ms."));
                    // 删除ClientRequest
                    requestIterator.remove();
                } else
                    break;
            }
            // 队列已经为空，则从unsent集合中删除
            if (requestEntry.getValue().isEmpty())
                iterator.remove();
        }
    }

    protected void failUnsentRequests(Node node, RuntimeException e) {
        // clear unsent requests to node and fail their corresponding futures
        List<ClientRequest> unsentRequests = unsent.remove(node);
        if (unsentRequests != null) {
            for (ClientRequest request : unsentRequests) {
                RequestFutureCompletionHandler handler = (RequestFutureCompletionHandler) request.callback();
                handler.raise(e);
            }
        }
    }

    /**
     * 循环遍历ClientRequest列表，每次循环调用NetworkClient.ready方法
     * 若符合条件，则调用NetworkClient.send方法，将请求放入InFlightRequests队中等待响应
     */
    private boolean trySend(long now) {
        // send any requests that can be sent now
        boolean requestsSent = false;
        // 遍历unsent集合
        for (Map.Entry<Node, List<ClientRequest>> requestEntry: unsent.entrySet()) {
            Node node = requestEntry.getKey();
            Iterator<ClientRequest> iterator = requestEntry.getValue().iterator();
            while (iterator.hasNext()) {
                ClientRequest request = iterator.next();
                // 检测是否可以发送请求
                if (client.ready(node, now)) {
                    // 等待发送请求
                    client.send(request, now);
                    // 从集合中删除
                    iterator.remove();
                    requestsSent = true;
                }
            }
        }
        return requestsSent;
    }

    private void clientPoll(long timeout, long now) {
        client.poll(timeout, now);
        maybeTriggerWakeup();
    }

    // 是否有其他线程中断，如果有中断请求，则抛出异常
    private void maybeTriggerWakeup() {
        if (wakeupDisabledCount == 0 && wakeup.get()) {
            wakeup.set(false);
            throw new WakeupException();
        }
    }

    public void disableWakeups() {
        wakeupDisabledCount++;
    }

    public void enableWakeups() {
        if (wakeupDisabledCount <= 0)
            throw new IllegalStateException("Cannot enable wakeups since they were never disabled");

        wakeupDisabledCount--;

        // re-wakeup the client if the flag was set since previous wake-up call
        // could be cleared by poll(0) while wakeups were disabled
        if (wakeupDisabledCount == 0 && wakeup.get())
            this.client.wakeup();
    }

    @Override
    public void close() throws IOException {
        client.close();
    }

    /**
     * Find whether a previous connection has failed. Note that the failure state will persist until either
     * {@link #tryConnect(Node)} or {@link #send(Node, ApiKeys, AbstractRequest)} has been called.
     * @param node Node to connect to if possible
     */
    public boolean connectionFailed(Node node) {
        return client.connectionFailed(node);
    }

    /**
     * Initiate a connection if currently possible. This is only really useful for resetting the failed
     * status of a socket. If there is an actual request to send, then {@link #send(Node, ApiKeys, AbstractRequest)}
     * should be used.
     * @param node The node to connect to
     */
    public void tryConnect(Node node) {
        client.ready(node, time.milliseconds());
    }

    public static class RequestFutureCompletionHandler
            extends RequestFuture<ClientResponse>
            implements RequestCompletionHandler {

        // 回调函数
        @Override
        public void onComplete(ClientResponse response) {
            // 因连接故障而产生的ClientResponse对象
            if (response.wasDisconnected()) {
                ClientRequest request = response.request();
                RequestSend send = request.request();
                ApiKeys api = ApiKeys.forId(send.header().apiKey());
                int correlation = send.header().correlationId();
                log.debug("Cancelled {} request {} with correlation id {} due to node {} being disconnected",
                        api, request, correlation, send.destination());
                // 调用 继承自父类RequestFuture的raise方法
                raise(DisconnectException.INSTANCE);
            } else {
                // 继承自父类的complete方法
                complete(response);
            }
        }
    }
}
