package com.coffeepointordersystem.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.kafka.clients.admin.NewTopic;
import org.junit.jupiter.api.Test;

class OrderCompletedKafkaTopicConfigTest {

	@Test
	void orderCompletedTopic_createsThreePartitions() {
		OrderCompletedKafkaTopicConfig config = new OrderCompletedKafkaTopicConfig();

		NewTopic topic = config.orderCompletedTopic();

		assertThat(topic.name()).isEqualTo(OrderCompletedKafkaTopicConfig.ORDER_COMPLETED_TOPIC);
		assertThat(topic.numPartitions()).isEqualTo(OrderCompletedKafkaTopicConfig.PARTITION_COUNT);
		assertThat(topic.replicationFactor()).isEqualTo((short) 1);
	}

}
