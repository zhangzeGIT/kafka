package com.zhangze.test;

import org.apache.kafka.clients.producer.*;
import scala.Int;

import java.util.Properties;
import java.util.concurrent.ExecutionException;

public class ProducerDemo {
    // topic
    private static final String topic = "test";

    public static void main(String[] args) {
        // 消息发送方式，同步还是异步
        boolean isAsync = args.length == 0 || !args[0].trim().equalsIgnoreCase("sync");

        // 环境变量添加，需要输入配置文件的路径
//        System.setProperty("java.security.auth.login.config", "D:/myConf/kafka_client_jaas.conf");
        Properties properties = new Properties();
        // 权限验证参数
//        properties.put("security.protocol", "SASL_PLAINTEXT");
//        properties.put("sasl.mechanism", "PLAIN");

        // kafka
        properties.put("bootstrap.servers", "localhost:9092");
        // 客户端ID
        properties.put("client.id", "DemoProducer");
        // 消息都是字节数组，可以配置序列化器
        properties.put("key.serializer", "org.apache.kafka.common.serialization.IntegerSerializer");
        properties.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        // 设置interceptor，在发送之前和收到ACK返回之前执行
        properties.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, "com.zhangze.test.StaticsInterceptor");
        // 生产者核心类，线程安全，多个线程可共享一个producer
        KafkaProducer<Integer, String> producer = new KafkaProducer<>(properties);

        // 消息key
        int messageNo = 1;
        while (true) {
            String messageStr = "Message_" + messageNo;
            long startTime = System.currentTimeMillis();
            // 异步发送消息
            if (isAsync) {
                // 第一个参数封装了目标topic，key，value
                // 第二个参数，当生产者接收到Kafka发来的ACK确认消息时，调用onCompletion方法
                producer.send(new ProducerRecord<>(topic, messageNo, messageStr),
                        new DemoCallBack(startTime, messageNo, messageStr));
            }else {
                // 同步发送
                try {
                    // send方法返回类型是Future<RecordMetadata>
                    // get方法阻塞当前线程，等待kafka服务端ACK响应
                    producer.send(new ProducerRecord<>(topic, messageNo, messageStr)).get();
                } catch (InterruptedException | ExecutionException e) {
                    e.printStackTrace();
                }
            }
            ++messageNo;
        }
    }
}
// 回调函数
class DemoCallBack implements Callback {
    private final long startTime;
    private final int key;
    private final String message;

    DemoCallBack(long startTime, int key, String message) {
        this.startTime = startTime;
        this.key = key;
        this.message = message;
    }

    public void onCompletion(RecordMetadata metadata, Exception exception) {
        long elapsedTime = System.currentTimeMillis() - startTime;
        if (metadata != null) {
            // record meta data中包含了分区信息，offset信息等
            System.out.printf("message("+key+", "+message+") sent to partition(" +
                    metadata.partition() + "), "+
                    "offset(" + metadata.offset() + ") in " +
                    elapsedTime + "ms");
        }
    }
}
