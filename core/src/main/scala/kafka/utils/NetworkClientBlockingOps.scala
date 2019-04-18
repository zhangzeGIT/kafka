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

package kafka.utils

import java.io.IOException
import org.apache.kafka.clients.{ClientRequest, ClientResponse, NetworkClient}
import org.apache.kafka.common.Node

import scala.annotation.tailrec
import scala.collection.JavaConverters._

import org.apache.kafka.common.utils.{Time => JTime}

object NetworkClientBlockingOps {
  implicit def networkClientBlockingOps(client: NetworkClient): NetworkClientBlockingOps =
    new NetworkClientBlockingOps(client)
}

/**
 * Provides extension methods for `NetworkClient` that are useful for implementing blocking behaviour. Use with care.
 *
 * Example usage:
 *
 * {{{
 * val networkClient: NetworkClient = ...
 * import NetworkClientBlockingOps._
 * networkClient.blockingReady(...)
 * }}}
 */
class NetworkClientBlockingOps(val client: NetworkClient) extends AnyVal {

  /**
   * Invokes `client.ready` followed by 0 or more `client.poll` invocations until the connection to `node` is ready,
   * the timeout expires or the connection fails.
   *
   * It returns `true` if the call completes normally or `false` if the timeout expires. If the connection fails,
   * an `IOException` is thrown instead.
   *
   * This method is useful for implementing blocking behaviour on top of the non-blocking `NetworkClient`, use it with
   * care.
   */
  // 阻塞等待知道指定Node处于ready状态
  def blockingReady(node: Node, timeout: Long)(implicit time: JTime): Boolean = {
    require(timeout >=0, "timeout should be >= 0")
    client.ready(node, time.milliseconds()) || pollUntil(timeout) { (_, now) =>
      // 检测node是否ready
      if (client.isReady(node, now))
        true
      else if (client.connectionFailed(node))
        throw new IOException(s"Connection to $node failed")
      else false
    }
  }

  /**
   * Invokes `client.send` followed by 1 or more `client.poll` invocations until a response is received or a
   * disconnection happens (which can happen for a number of reasons including a request timeout).
   *
   * In case of a disconnection, an `IOException` is thrown.
   *
   * This method is useful for implementing blocking behaviour on top of the non-blocking `NetworkClient`, use it with
   * care.
   */
  // 发送请求后阻塞等待响应
  def blockingSendAndReceive(request: ClientRequest)(implicit time: JTime): ClientResponse = {
    // 发送请求
    client.send(request, time.milliseconds())

    pollContinuously { responses =>
      // 找到上面的发送请求对应的响应
      val response = responses.find { response =>
        response.request.request.header.correlationId == request.request.header.correlationId
      }
      // 链接断开的时候，抛出异常
      response.foreach { r =>
        if (r.wasDisconnected) {
          val destination = request.request.destination
          throw new IOException(s"Connection to $destination was disconnected before the response was read")
        }
      }
      response
    }

  }

  /**
   * Invokes `client.poll` until `predicate` returns `true` or the timeout expires.
   *
   * It returns `true` if the call completes normally or `false` if the timeout expires. Exceptions thrown via
   * `predicate` are not handled and will bubble up.
   *
   * This method is useful for implementing blocking behaviour on top of the non-blocking `NetworkClient`, use it with
   * care.
   */
  // 递归实现阻塞的
  private def pollUntil(timeout: Long)(predicate: (Seq[ClientResponse], Long) => Boolean)(implicit time: JTime): Boolean = {
    val methodStartTime = time.milliseconds()
    // 计算超时时间
    val timeoutExpiryTime = methodStartTime + timeout

    @tailrec
    def recursivePoll(iterationStartTime: Long): Boolean = {
      val pollTimeout = timeoutExpiryTime - iterationStartTime
      val responses = client.poll(pollTimeout, iterationStartTime).asScala
      // 检测是否递归条件结束
      if (predicate(responses, iterationStartTime)) true// 链接建立
      else {
        val afterPollTime = time.milliseconds()
        // 未超时，继续递归
        if (afterPollTime < timeoutExpiryTime) recursivePoll(afterPollTime)
        else false
      }
    }
    // 递归入口
    recursivePoll(methodStartTime)
  }

  /**
    * Invokes `client.poll` until `collect` returns `Some`. The value inside `Some` is returned.
    *
    * Exceptions thrown via `collect` are not handled and will bubble up.
    *
    * This method is useful for implementing blocking behaviour on top of the non-blocking `NetworkClient`, use it with
    * care.
    */
  private def pollContinuously[T](collect: Seq[ClientResponse] => Option[T])(implicit time: JTime): T = {

    @tailrec
    def recursivePoll: T = {
      // rely on request timeout to ensure we don't block forever
      // 下面的poll设置的超时时间虽然是Long.MaxValue，但是并不会永久阻塞
      val responses = client.poll(Long.MaxValue, time.milliseconds()).asScala
      // 检测是否满足递归条件结束
      collect(responses) match {
        case Some(result) => result// 递归结束
        case None => recursivePoll// 继续递归
      }
    }
    // 递归入口
    recursivePoll
  }

}
