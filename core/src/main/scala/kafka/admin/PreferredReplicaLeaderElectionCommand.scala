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
package kafka.admin

import joptsimple.OptionParser
import kafka.utils._
import org.I0Itec.zkclient.ZkClient
import org.I0Itec.zkclient.exception.ZkNodeExistsException
import kafka.common.{TopicAndPartition, AdminCommandFailedException}
import collection._
import org.apache.kafka.common.utils.Utils
import org.apache.kafka.common.security.JaasUtils

object PreferredReplicaLeaderElectionCommand extends Logging {

  // path-to-json-file餐宿指定一个优先副本选举的分区，该JSON格式的输入文件示例如下：
  // {
  //    "partitions":[{
  //      "topic":"foo","partition":1
  //    },{
  //      "topic":"foobar","partition":2
  //    }]
  // }
  // 未指定输入文件，默认所有分区都需要进行优先副本选举操作
  def main(args: Array[String]): Unit = {
    // 检测path-to-json-file参数个ZK参数
    val parser = new OptionParser
    val jsonFileOpt = parser.accepts("path-to-json-file", "The JSON file with the list of partitions " +
      "for which preferred replica leader election should be done, in the following format - \n" +
       "{\"partitions\":\n\t[{\"topic\": \"foo\", \"partition\": 1},\n\t {\"topic\": \"foobar\", \"partition\": 2}]\n}\n" +
      "Defaults to all existing partitions")
      .withRequiredArg
      .describedAs("list of partitions for which preferred replica leader election needs to be triggered")
      .ofType(classOf[String])
    val zkConnectOpt = parser.accepts("zookeeper", "REQUIRED: The connection string for the zookeeper connection in the " +
      "form host:port. Multiple URLS can be given to allow fail-over.")
      .withRequiredArg
      .describedAs("urls")
      .ofType(classOf[String])
      
    if(args.length == 0)
      CommandLineUtils.printUsageAndDie(parser, "This tool causes leadership for each partition to be transferred back to the 'preferred replica'," + 
                                                " it can be used to balance leadership among the servers.")

    val options = parser.parse(args : _*)

    CommandLineUtils.checkRequiredArgs(parser, options, zkConnectOpt)

    val zkConnect = options.valueOf(zkConnectOpt)
    var zkClient: ZkClient = null
    var zkUtils: ZkUtils = null
    try {
      // 创建ZK的链接
      zkClient = ZkUtils.createZkClient(zkConnect, 30000, 30000)
      zkUtils = ZkUtils(zkConnect, 
                        30000,
                        30000,
                        JaasUtils.isZkSecurityEnabled())
      // 获取需要进行优先副本选举的分区集合
      val partitionsForPreferredReplicaElection =
      // 未指定path-to-json-file参数则返回全部分区
        if (!options.has(jsonFileOpt))
          zkUtils.getAllPartitions()
        else
        // 解析json参数
          parsePreferredReplicaElectionData(Utils.readFileAsString(options.valueOf(jsonFileOpt)))
      // 创建这个对象
      val preferredReplicaElectionCommand = new PreferredReplicaLeaderElectionCommand(zkUtils, partitionsForPreferredReplicaElection)

      // 将指定的分区写入到ZK中的/admin/preferred_replica_election节点中
      preferredReplicaElectionCommand.moveLeaderToPreferredReplica()
      println("Successfully started preferred replica election for partitions %s".format(partitionsForPreferredReplicaElection))
    } catch {
      case e: Throwable =>
        println("Failed to start preferred replica election")
        println(Utils.stackTrace(e))
    } finally {
      if (zkClient != null)
        zkClient.close()
    }
  }

  def parsePreferredReplicaElectionData(jsonString: String): immutable.Set[TopicAndPartition] = {
    Json.parseFull(jsonString) match {
      case Some(m) =>
        m.asInstanceOf[Map[String, Any]].get("partitions") match {
          case Some(partitionsList) =>
            val partitionsRaw = partitionsList.asInstanceOf[List[Map[String, Any]]]
            val partitions = partitionsRaw.map { p =>
              val topic = p.get("topic").get.asInstanceOf[String]
              val partition = p.get("partition").get.asInstanceOf[Int]
              TopicAndPartition(topic, partition)
            }
            val duplicatePartitions = CoreUtils.duplicates(partitions)
            val partitionsSet = partitions.toSet
            if (duplicatePartitions.nonEmpty)
              throw new AdminOperationException("Preferred replica election data contains duplicate partitions: %s".format(duplicatePartitions.mkString(",")))
            partitionsSet
          case None => throw new AdminOperationException("Preferred replica election data is empty")
        }
      case None => throw new AdminOperationException("Preferred replica election data is empty")
    }
  }

  // 将需要进行优先副本选举的分区写入ZK
  def writePreferredReplicaElectionData(zkUtils: ZkUtils,
                                        partitionsUndergoingPreferredReplicaElection: scala.collection.Set[TopicAndPartition]) {
    // /admin/preferred_replica_election节点的路径
    val zkPath = ZkUtils.PreferredReplicaLeaderElectionPath
    val partitionsList = partitionsUndergoingPreferredReplicaElection.map(e => Map("topic" -> e.topic, "partition" -> e.partition))
    // 转换成JSON格式
    val jsonData = Json.encode(Map("version" -> 1, "partitions" -> partitionsList))
    try {
      zkUtils.createPersistentPath(zkPath, jsonData)
      info("Created preferred replica election path with %s".format(jsonData))
    } catch {
      case nee: ZkNodeExistsException =>
        val partitionsUndergoingPreferredReplicaElection =
          PreferredReplicaLeaderElectionCommand.parsePreferredReplicaElectionData(zkUtils.readData(zkPath)._1)
        throw new AdminOperationException("Preferred replica leader election currently in progress for " +
          "%s. Aborting operation".format(partitionsUndergoingPreferredReplicaElection))
      case e2: Throwable => throw new AdminOperationException(e2.toString)
    }
  }
}

class PreferredReplicaLeaderElectionCommand(zkUtils: ZkUtils, partitions: scala.collection.Set[TopicAndPartition])
  extends Logging {
  // 首先，检测指定的topic是否包含指定的分区
  // 之后，调用wpred方法，将需要进行优先副本选举的分区信息写入ZK
  def moveLeaderToPreferredReplica() = {
    try {
      val validPartitions = partitions.filter(p => validatePartition(zkUtils, p.topic, p.partition))
      PreferredReplicaLeaderElectionCommand.writePreferredReplicaElectionData(zkUtils, validPartitions)
    } catch {
      case e: Throwable => throw new AdminCommandFailedException("Admin command failed", e)
    }
  }

  def validatePartition(zkUtils: ZkUtils, topic: String, partition: Int): Boolean = {
    // check if partition exists
    val partitionsOpt = zkUtils.getPartitionsForTopics(List(topic)).get(topic)
    partitionsOpt match {
      case Some(partitions) =>
        if(partitions.contains(partition)) {
          true
        } else {
          error("Skipping preferred replica leader election for partition [%s,%d] ".format(topic, partition) +
            "since it doesn't exist")
          false
        }
      case None => error("Skipping preferred replica leader election for partition " +
        "[%s,%d] since topic %s doesn't exist".format(topic, partition, topic))
        false
    }
  }
}
