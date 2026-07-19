package com.coffeepointordersystem.infra.kafka;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrderCompletedKafkaTopicConfig {

	public static final String ORDER_COMPLETED_TOPIC = "order.completed";
	public static final int PARTITION_COUNT = 3;
	private static final short REPLICATION_FACTOR = 1;

	@Bean
	public NewTopic orderCompletedTopic() {
		return new NewTopic(ORDER_COMPLETED_TOPIC, PARTITION_COUNT, REPLICATION_FACTOR);
	}

}
