package com.zhangze.test;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;

import java.util.Arrays;
import java.util.Properties;

public class KafkaConsumerDemo {
    public static void main(String[] args) {
        Properties props = new Properties();
        // broker的地址
        props.put("bootstrap.servers","localhost:9092");
        // 所属consumer group的ID
        props.put("group.id", "test");
        // 自动提交offset，每次调用poll时，都会检测是否需要提交
        props.put("auto.commit.interval.ms", "1000");
        props.put("session.timeout.ms", "30000");
        props.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        props.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props);

        // 订阅test1，test2两个topic
        consumer.subscribe(Arrays.asList("test1","test2"));
        try {
            while(true) {
                // 从服务端拉取消息，每个poll可以拉取多个消息
                ConsumerRecords<String, String> records = consumer.poll(100);
                // 消费消息，这里仅仅是将消息的offset，key，value输出
                for (ConsumerRecord<String, String> record : records){
                    System.out.printf("offset = %d, key = %s, value = %s\n", record.offset(), record.key(), record.value());
                }
            }
        }finally {
            consumer.close();
        }

    }
}
