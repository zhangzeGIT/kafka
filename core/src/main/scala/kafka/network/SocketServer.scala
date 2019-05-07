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

package kafka.network

import java.io.IOException
import java.net._
import java.nio.channels._
import java.nio.channels.{Selector => NSelector}
import java.util
import java.util.concurrent._
import java.util.concurrent.atomic._

import com.yammer.metrics.core.Gauge
import kafka.cluster.{BrokerEndPoint, EndPoint}
import kafka.common.KafkaException
import kafka.metrics.KafkaMetricsGroup
import kafka.server.KafkaConfig
import kafka.utils._
import org.apache.kafka.common.metrics._
import org.apache.kafka.common.network.{ChannelBuilders, KafkaChannel, LoginType, Mode, Selector => KSelector}
import org.apache.kafka.common.security.auth.KafkaPrincipal
import org.apache.kafka.common.protocol.SecurityProtocol
import org.apache.kafka.common.protocol.types.SchemaException
import org.apache.kafka.common.utils.{Time, Utils}

import scala.collection._
import JavaConverters._
import scala.util.control.{ControlThrowable, NonFatal}

/**
 * An NIO socket server. The threading model is
 *   1 Acceptor thread that handles new connections
 *   Acceptor has N Processor threads that each have their own selector and read requests from sockets
 *   M Handler threads that handle requests and produce responses back to the processor threads for writing.
 */
// 一个网卡一个Endpoint一个Acceptor
// Acceptor接受并处理所有新连接
//    Acceptor对应多个Processor线程
// Processor对应多个Handler线程
//    通过RequestChannel通信
class SocketServer(val config: KafkaConfig, val metrics: Metrics, val time: Time) extends Logging with KafkaMetricsGroup {

  // 服务器一般有多个网卡，可以配置多个IP，Kafka可以同时监听多个端口
  // 封装了需要监听的host，port及使用的网络协议
  // 每个Endpoint会创建一个对应的Acceptor
  private val endpoints = config.listeners
  // Processor线程的个数
  private val numProcessorThreads = config.numNetworkThreads
  // RequestChannel的requestQueue中缓存的最大请求个数
  private val maxQueuedRequests = config.queuedMaxRequests
  // Processor线程的总个数
  private val totalProcessorThreads = numProcessorThreads * endpoints.size

  // 每个IP能创建的最大连接数
  private val maxConnectionsPerIp = config.maxConnectionsPerIp
  // 具体指某个IP上最大的连接数，这里指定的会覆盖上面指定的
  private val maxConnectionsPerIpOverrides = config.maxConnectionsPerIpOverrides

  this.logIdent = "[Socket Server on Broker " + config.brokerId + "], "

  // Process线程与Handler线程之间交换数据的队列
  // RequestChannel中包含一个requestQueue队列和多个responseQueues队列
  // Processor线程将读取到的请求存入requestQueue中
  // Handler线程从requestQueue队列中取出进行处理
  // 处理完产生的响应会存放到Processor对应的responseQueue中
  // Processor从responseQueue中取出响应，发送给客户端
  val requestChannel = new RequestChannel(totalProcessorThreads, maxQueuedRequests)
  // Processor线程的集合，此集合中包含所有Endpoint对应的Processors线程
  private val processors = new Array[Processor](totalProcessorThreads)

  // Acceptor对象，每个Endpoint对应一个Acceptor对象
  private[network] val acceptors = mutable.Map[EndPoint, Acceptor]()
  // connectionQuotas类型的对象，提供了控制每个IP上的最大连接数的功能
  // 底层Map，创建新的时，与maxConnectionsPerIpOverrides指定的最大值进行比较
  private var connectionQuotas: ConnectionQuotas = _

  private val allMetricNames = (0 until totalProcessorThreads).map { i =>
    val tags = new util.HashMap[String, String]()
    tags.put("networkProcessor", i.toString)
    metrics.metricName("io-wait-ratio", "socket-server-metrics", tags)
  }

  /**
   * Start the socket server
   * 初始化核心代码
   */
  def startup() {
    this.synchronized {

      connectionQuotas = new ConnectionQuotas(maxConnectionsPerIp, maxConnectionsPerIpOverrides)

      // Socket的sendBuffer大小
      val sendBufferSize = config.socketSendBufferBytes
      // Socket的receiveBuffer大小
      val recvBufferSize = config.socketReceiveBufferBytes
      val brokerId = config.brokerId

      var processorBeginIndex = 0
      // 遍历endpoint集合
      endpoints.values.foreach { endpoint =>
        val protocol = endpoint.protocolType

        val processorEndIndex = processorBeginIndex + numProcessorThreads
        // processors数组从processorBeginIndex到processorEndIndex
        // 都是当前endpoint对应的processor集合
        for (i <- processorBeginIndex until processorEndIndex)
          //创建processor对象
          processors(i) = newProcessor(i, connectionQuotas, protocol)

        // 创建acceptor
        // 创建processor对应的线程
        // 第五个参数指定了processors数组中与此Acceptor对象对应的Processor对象
        val acceptor = new Acceptor(endpoint, sendBufferSize, recvBufferSize, brokerId,
          processors.slice(processorBeginIndex, processorEndIndex), connectionQuotas)
        acceptors.put(endpoint, acceptor)
        // 创建Acceptor对应的线程，并启动
        Utils.newThread("kafka-socket-acceptor-%s-%d".format(protocol.toString, endpoint.port), acceptor, false).start()
        // 主线程阻塞等待Acceptor线程启动完成
        acceptor.awaitStartup()

        // 修改processorBeginIndex,为下一个Endpoint准备
        processorBeginIndex = processorEndIndex
      }
    }

    newGauge("NetworkProcessorAvgIdlePercent",
      new Gauge[Double] {
        def value = allMetricNames.map( metricName =>
          metrics.metrics().get(metricName).value()).sum / totalProcessorThreads
      }
    )

    info("Started " + acceptors.size + " acceptor threads")
  }

  // register the processor threads for notification of responses
  // 向requestChannel中添加一个监听器，此监听器实现的功能是：
  // 当handler线程向某个responseQueue中写入数据时，会唤醒对应Processor线程进行处理
  requestChannel.addResponseListener(id => processors(id).wakeup())

  /**
   * Shutdown the socket server
   * 关闭所有的acceptor和processor
   */
  def shutdown() = {
    info("Shutting down")
    this.synchronized {
      acceptors.values.foreach(_.shutdown)
      processors.foreach(_.shutdown)
    }
    info("Shutdown completed")
  }

  def boundPort(protocol: SecurityProtocol = SecurityProtocol.PLAINTEXT): Int = {
    try {
      acceptors(endpoints(protocol)).serverChannel.socket().getLocalPort
    } catch {
      case e: Exception => throw new KafkaException("Tried to check server's port before server was started or checked for port of non-existing protocol", e)
    }
  }

  /* `protected` for test usage */
  protected[network] def newProcessor(id: Int, connectionQuotas: ConnectionQuotas, protocol: SecurityProtocol): Processor = {
    new Processor(id,
      time,
      config.socketRequestMaxBytes,
      requestChannel,
      connectionQuotas,
      config.connectionsMaxIdleMs,
      protocol,
      config.values,
      metrics
    )
  }

  /* For test usage */
  private[network] def connectionCount(address: InetAddress): Int =
    Option(connectionQuotas).fold(0)(_.get(address))

  /* For test usage */
  private[network] def processor(index: Int): Processor = processors(index)

}

/**
 * A base class with some helper variables and methods
 */
private[kafka] abstract class AbstractServerThread(connectionQuotas: ConnectionQuotas) extends Runnable with Logging {

  // 标识当前startup操作是否完成
  private val startupLatch = new CountDownLatch(1)
  // 标识当前shutdown操作是否完成
  private val shutdownLatch = new CountDownLatch(1)
  // 标识当前线程是否存活，shutdown方法中会将alive设置为true
  private val alive = new AtomicBoolean(true)

  def wakeup()

  /**
   * Initiates a graceful shutdown by signaling to stop and waiting for the shutdown to complete
   */
  def shutdown(): Unit = {
    alive.set(false)
    wakeup()
    shutdownLatch.await()
  }

  /**
   * Wait for the thread to completely start up
   */
  def awaitStartup(): Unit = startupLatch.await

  /**
   * Record that the thread startup is complete
   */
  protected def startupComplete() = {
    startupLatch.countDown()
  }

  /**
   * Record that the thread shutdown is complete
   */
  protected def shutdownComplete() = shutdownLatch.countDown()

  /**
   * Is the server still running?
   */
  protected def isRunning = alive.get

  /**
   * Close the connection identified by `connectionId` and decrement the connection count.
   */
  def close(selector: KSelector, connectionId: String) {
    // 找到connectionID对应的链接
    val channel = selector.channel(connectionId)
    if (channel != null) {
      debug(s"Closing selector connection $connectionId")
      val address = channel.socketAddress
      if (address != null)
      // 修改connectionQuotas记录的连接数
        connectionQuotas.dec(address)
      // 关闭连接
      selector.close(connectionId)
    }
  }

  /**
   * Close `channel` and decrement the connection count.
   */
  def close(channel: SocketChannel) {
    if (channel != null) {
      debug("Closing connection from " + channel.socket.getRemoteSocketAddress())
      connectionQuotas.dec(channel.socket.getInetAddress)
      swallowError(channel.socket().close())
      swallowError(channel.close())
    }
  }
}

/**
 * Thread that accepts and configures new connections. There is one of these per endpoint.
  * 接收客户端建立连接请求，创建Socket连接并分配给Processor处理
 */
private[kafka] class Acceptor(val endPoint: EndPoint,
                              val sendBufferSize: Int,
                              val recvBufferSize: Int,
                              brokerId: Int,
                              processors: Array[Processor],
                              connectionQuotas: ConnectionQuotas) extends AbstractServerThread(connectionQuotas) with KafkaMetricsGroup {

  // 创建nioSelector
  private val nioSelector = NSelector.open()
  // 创建ServerSocketChannel
  val serverChannel = openServerSocket(endPoint.host, endPoint.port)

  // 同步
  this.synchronized {
    // 对每个processor都创建对应的线程并启动
    processors.foreach { processor =>
      Utils.newThread("kafka-network-thread-%d-%s-%d".format(brokerId, endPoint.protocolType.toString, processor.id), processor, false).start()
    }
  }

  /**
   * Accept loop that checks for new connection attempts
    * 核心方法，完成对OP_ACCEPT事件的处理
   */
  def run() {
    // 注册OP_ACCEPT事件
    serverChannel.register(nioSelector, SelectionKey.OP_ACCEPT)
    // 标识当前线程启动操作已经完成
    startupComplete()
    try {
      var currentProcessor = 0
      // 检测线程运行状态
      while (isRunning) {
        try {
          // 等待关闭的事件
          val ready = nioSelector.select(500)
          if (ready > 0) {
            val keys = nioSelector.selectedKeys()
            val iter = keys.iterator()
            while (iter.hasNext && isRunning) {
              try {
                val key = iter.next
                iter.remove()
                if (key.isAcceptable)
                // 调用accept方法处理OP_ACCEPT事件
                  accept(key, processors(currentProcessor))
                else
                  throw new IllegalStateException("Unrecognized key state for acceptor thread.")

                // round robin to the next processor thread
                // 更新currentProcessor，使用Round-Robin的方式选择
                currentProcessor = (currentProcessor + 1) % processors.length
              } catch {
                case e: Throwable => error("Error while accepting connection", e)
              }
            }
          }
        }
        catch {
          // We catch all the throwables to prevent the acceptor thread from exiting on exceptions due
          // to a select operation on a specific channel or a bad request. We don't want the
          // the broker to stop responding to requests from other clients in these scenarios.
          case e: ControlThrowable => throw e
          case e: Throwable => error("Error occurred", e)
        }
      }
    } finally {
      debug("Closing server socket and selector.")
      swallowError(serverChannel.close())
      swallowError(nioSelector.close())
      // 标记线程关闭操作已完成
      shutdownComplete()
    }
  }

  /*
   * Create a server socket to listen for connections on.
   */
  private def openServerSocket(host: String, port: Int): ServerSocketChannel = {
    val socketAddress =
      if(host == null || host.trim.isEmpty)
        new InetSocketAddress(port)
      else
        new InetSocketAddress(host, port)
    val serverChannel = ServerSocketChannel.open()
    serverChannel.configureBlocking(false)
    serverChannel.socket().setReceiveBufferSize(recvBufferSize)
    try {
      serverChannel.socket.bind(socketAddress)
      info("Awaiting socket connections on %s:%d.".format(socketAddress.getHostString, serverChannel.socket.getLocalPort))
    } catch {
      case e: SocketException =>
        throw new KafkaException("Socket server failed to bind to %s:%d: %s.".format(socketAddress.getHostString, port, e.getMessage), e)
    }
    serverChannel
  }

  /*
   * Accept a new connection
   * 实现对OP_ACCEPT事件的处理
   * 创建SocketChannel并将其交给Processor.accept处理
   */
  def accept(key: SelectionKey, processor: Processor) {
    val serverSocketChannel = key.channel().asInstanceOf[ServerSocketChannel]
    // 创建SocketChannel
    val socketChannel = serverSocketChannel.accept()
    try {
      // 增加ConnectionQuotas中记录的连接数
      connectionQuotas.inc(socketChannel.socket().getInetAddress)
      socketChannel.configureBlocking(false)
      socketChannel.socket().setTcpNoDelay(true)
      socketChannel.socket().setKeepAlive(true)
      socketChannel.socket().setSendBufferSize(sendBufferSize)

      debug("Accepted connection from %s on %s and assigned it to processor %d, sendBufferSize [actual|requested]: [%d|%d] recvBufferSize [actual|requested]: [%d|%d]"
            .format(socketChannel.socket.getRemoteSocketAddress, socketChannel.socket.getLocalSocketAddress, processor.id,
                  socketChannel.socket.getSendBufferSize, sendBufferSize,
                  socketChannel.socket.getReceiveBufferSize, recvBufferSize))

      // 将socketChannel交给processor处理
      processor.accept(socketChannel)
    } catch {
      case e: TooManyConnectionsException =>
        info("Rejected connection from %s, address already has the configured maximum of %d connections.".format(e.ip, e.count))
        close(socketChannel)
    }
  }

  /**
   * Wakeup the thread for selection.
   */
  @Override
  def wakeup = nioSelector.wakeup()

}

/**
 * Thread that processes all requests from a single connection. There are N of these running in parallel
 * each of which has its own selector
  * 读取请求和写会响应操作
  * 不参与具体的业务逻辑处理
 */
private[kafka] class Processor(val id: Int,
                               time: Time,
                               maxRequestSize: Int,
                               requestChannel: RequestChannel,
                               connectionQuotas: ConnectionQuotas,
                               connectionsMaxIdleMs: Long,
                               protocol: SecurityProtocol,
                               channelConfigs: java.util.Map[String, _],
                               metrics: Metrics) extends AbstractServerThread(connectionQuotas) with KafkaMetricsGroup {

  private object ConnectionId {
    def fromString(s: String): Option[ConnectionId] = s.split("-") match {
      case Array(local, remote) => BrokerEndPoint.parseHostPort(local).flatMap { case (localHost, localPort) =>
        BrokerEndPoint.parseHostPort(remote).map { case (remoteHost, remotePort) =>
          ConnectionId(localHost, localPort, remoteHost, remotePort)
        }
      }
      case _ => None
    }
  }

  private case class ConnectionId(localHost: String, localPort: Int, remoteHost: String, remotePort: Int) {
    override def toString: String = s"$localHost:$localPort-$remoteHost:$remotePort"
  }

  // 保存由此Processor处理的新建的SocketChannel
  private val newConnections = new ConcurrentLinkedQueue[SocketChannel]()
  // 保存未发送的响应
  private val inflightResponses = mutable.Map[String, RequestChannel.Response]()
  private val metricTags = Map("networkProcessor" -> id.toString).asJava

  newGauge("IdlePercent",
    new Gauge[Double] {
      def value = {
        metrics.metrics().get(metrics.metricName("io-wait-ratio", "socket-server-metrics", metricTags)).value()
      }
    },
    metricTags.asScala
  )

  // 负责网络管理，参考第二章KSelector
  private val selector = new KSelector(
    maxRequestSize,
    connectionsMaxIdleMs,
    metrics,
    time,
    "socket-server",
    metricTags,
    false,
    ChannelBuilders.create(protocol, Mode.SERVER, LoginType.SERVER, channelConfigs, null, true))

  /**
    * 从网络上读写数据功能
    */
  override def run() {
    // 标识Processor的初始化流程已经结束，唤醒阻塞等待此processor初始化完成的线程
    startupComplete()
    while (isRunning) {
      try {
        // setup any new connections that have been queued up
        // 处理newConnections队列中的新建SocketChannel
        // 队列中每个sc都要在nioSelector上注册OP_READ事件
        // socket channel会被封装成KafkaChannel
        configureNewConnections()
        // register any new responses for writing

        // 获取RequestChannel对应的responseQueue队列，并处理其中缓存的Response
        processNewResponses()
        // 调用SocketServer.poll方法读取请求，发送响应
        // 底层调用KSelector.poll方法，此方法会将读取的请求，发送成功的请求以及断开的请求放入其
        // completedReceives，completedSends，disconnected队列中
        poll()
        // 分别处理这三个队列
        processCompletedReceives()
        processCompletedSends()
        processDisconnected()
      } catch {
        // We catch all the throwables here to prevent the processor thread from exiting. We do this because
        // letting a processor exit might cause a bigger impact on the broker. Usually the exceptions thrown would
        // be either associated with a specific socket channel or a bad request. We just ignore the bad socket channel
        // or request. This behavior might need to be reviewed if we see an exception that need the entire broker to stop.
        case e: ControlThrowable => throw e
        case e: Throwable =>
          error("Processor got uncaught exception.", e)
      }
    }

    debug("Closing selector - processor " + id)
    swallowError(closeAll())
    shutdownComplete()
  }

  private def processNewResponses() {
    // requestChannel中使用Processor的ID绑定与responseQueue的对应关系
    // 获取对应responseQueue中的响应
    var curr = requestChannel.receiveResponse(id)
    while (curr != null) {
      try {
        curr.responseAction match {
          // 没有响应发送给客户端，注册OP_READ事件
          case RequestChannel.NoOpAction =>
            // There is no response to send to the client, we need to read more pipelined requests
            // that are sitting in the server's socket buffer
            curr.request.updateRequestMetrics
            trace("Socket server received empty response to send, registering for read: " + curr)
            selector.unmute(curr.request.connectionId)
          // 该响应需要发送给客户端，调用KSelector.send方法，并将响应放入inflightResponse队列缓存
          case RequestChannel.SendAction =>
            sendResponse(curr)
          // 关闭对应连接
          case RequestChannel.CloseConnectionAction =>
            curr.request.updateRequestMetrics
            trace("Closing socket connection actively according to the response code.")
            close(selector, curr.request.connectionId)
        }
      } finally {
        // 继续处理responseQueue
        curr = requestChannel.receiveResponse(id)
      }
    }
  }

  /* `protected` for test usage */
  protected[network] def sendResponse(response: RequestChannel.Response) {
    trace(s"Socket server received response to send, registering for write and sending data: $response")
    val channel = selector.channel(response.responseSend.destination)
    // `channel` can be null if the selector closed the connection because it was idle for too long
    if (channel == null) {
      warn(s"Attempting to send response via channel for which there is no open connection, connection id $id")
      response.request.updateRequestMetrics()
    }
    else {
      selector.send(response.responseSend)
      inflightResponses += (response.request.connectionId -> response)
    }
  }

  private def poll() {
    try selector.poll(300)
    catch {
      case e @ (_: IllegalStateException | _: IOException) =>
        error(s"Closing processor $id due to illegal state or IO exception")
        swallow(closeAll())
        shutdownComplete()
        throw e
    }
  }

  // 这个方法确认客户端的身份
  // 遍历队列，将NetworkReceives processorID，身份认证信息
  // 一起封装成RequestChannel.Request对象放入RequestChannel.requestQueue队列中
  // 等待handler线程处理
  // 取消对应KafkaChannel注册的OP_READ事件，表示在发送响应之前，此链接不能再读取任何请求了
  private def processCompletedReceives() {
    selector.completedReceives.asScala.foreach { receive =>
      try {
        // 获取请求对应的KafkaChannel
        val channel = selector.channel(receive.source)
        // 创建KafkaChannel对应的session
        val session = RequestChannel.Session(new KafkaPrincipal(KafkaPrincipal.USER_TYPE, channel.principal.getName),
          channel.socketAddress)
        // 封装Request对象
        val req = RequestChannel.Request(processor = id, connectionId = receive.source, session = session, buffer = receive.payload, startTimeMs = time.milliseconds, securityProtocol = protocol)
        // 放入requestChannel.requestQueue队列等待处理
        requestChannel.sendRequest(req)
        // 取消OP_READ事件
        selector.mute(receive.source)
      } catch {
        case e @ (_: InvalidRequestException | _: SchemaException) =>
          // note that even though we got an exception, we can assume that receive.source is valid. Issues with constructing a valid receive object were handled earlier
          error(s"Closing socket for ${receive.source} because of error", e)
          close(selector, receive.source)
      }
    }
  }

  // 将inflightResponse中保存的对应Response删除
  // 为对应连接重新注册OP_READ事件，允许从该连接读取数据
  private def processCompletedSends() {
    selector.completedSends.asScala.foreach { send =>
      val resp = inflightResponses.remove(send.destination).getOrElse {
        throw new IllegalStateException(s"Send for ${send.destination} completed, but not in `inflightResponses`")
      }
      resp.request.updateRequestMetrics()
      selector.unmute(send.destination)
    }
  }

  // 从inflightResponse中删除该连接对应的所有Response
  // 减少ConnectionQuotas中记录的连接数
  private def processDisconnected() {
    selector.disconnected.asScala.foreach { connectionId =>
      val remoteHost = ConnectionId.fromString(connectionId).getOrElse {
        throw new IllegalStateException(s"connectionId has unexpected format: $connectionId")
      }.remoteHost
      inflightResponses.remove(connectionId).foreach(_.request.updateRequestMetrics())
      // the channel has been closed by the selector but the quotas still need to be updated
      connectionQuotas.dec(InetAddress.getByName(remoteHost))
    }
  }

  /**
   * Queue up a new connection for reading
   */
  def accept(socketChannel: SocketChannel) {
    // 将SocketChannel添加到队列中
    newConnections.add(socketChannel)
    // 通过调用KSelector.wakeup实现，最终调用底层的JAVA NIO Selector的wakeup方法
    wakeup()
  }

  /**
   * Register any new connections that have been queued up
   */
  private def configureNewConnections() {
    // 遍历newConnections队列
    while (!newConnections.isEmpty) {
      val channel = newConnections.poll()
      try {
        debug(s"Processor $id listening to new connection from ${channel.socket.getRemoteSocketAddress}")
        val localHost = channel.socket().getLocalAddress.getHostAddress
        val localPort = channel.socket().getLocalPort
        val remoteHost = channel.socket().getInetAddress.getHostAddress
        val remotePort = channel.socket().getPort
        // 根据上面创建connectionId
        val connectionId = ConnectionId(localHost, localPort, remoteHost, remotePort).toString
        // 注册OP_READ事件
        selector.register(connectionId, channel)
      } catch {
        // We explicitly catch all non fatal exceptions and close the socket to avoid a socket leak. The other
        // throwables will be caught in processor and logged as uncaught exceptions.
        case NonFatal(e) =>
          // need to close the channel here to avoid a socket leak.
          close(channel)
          error(s"Processor $id closed connection from ${channel.getRemoteAddress}", e)
      }
    }
  }

  /**
   * Close the selector and all open connections
   */
  private def closeAll() {
    selector.channels.asScala.foreach { channel =>
      close(selector, channel.id)
    }
    selector.close()
  }

  /* For test usage */
  private[network] def channel(connectionId: String): Option[KafkaChannel] =
    Option(selector.channel(connectionId))

  /**
   * Wakeup the thread for selection.
   */
  @Override
  def wakeup = selector.wakeup()

}

class ConnectionQuotas(val defaultMax: Int, overrideQuotas: Map[String, Int]) {

  private val overrides = overrideQuotas.map { case (host, count) => (InetAddress.getByName(host), count) }
  private val counts = mutable.Map[InetAddress, Int]()

  def inc(address: InetAddress) {
    counts.synchronized {
      val count = counts.getOrElseUpdate(address, 0)
      counts.put(address, count + 1)
      val max = overrides.getOrElse(address, defaultMax)
      if (count >= max)
        throw new TooManyConnectionsException(address, max)
    }
  }

  def dec(address: InetAddress) {
    counts.synchronized {
      val count = counts.getOrElse(address,
        throw new IllegalArgumentException(s"Attempted to decrease connection count for address with no connections, address: $address"))
      if (count == 1)
        counts.remove(address)
      else
        counts.put(address, count - 1)
    }
  }

  def get(address: InetAddress): Int = counts.synchronized {
    counts.getOrElse(address, 0)
  }

}

class TooManyConnectionsException(val ip: InetAddress, val count: Int) extends KafkaException("Too many connections from %s (maximum = %d)".format(ip, count))
