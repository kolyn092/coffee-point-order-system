package com.coffeepointordersystem.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import jakarta.validation.Validation;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties.AckMode;

@SuppressWarnings("unchecked")
class PopularMenuKafkaConsumerConfigTest {

	@Test
	void popularMenuKafkaListenerContainerFactory_configuresManualAcknowledgmentAndConfiguredConcurrency() {
		ConsumerFactory<String, OrderCompletedEvent> consumerFactory = mock(ConsumerFactory.class);
		PopularMenuKafkaConsumerProperties properties = new PopularMenuKafkaConsumerProperties();
		properties.setConcurrency(3);
		PopularMenuKafkaConsumerConfig config = new PopularMenuKafkaConsumerConfig();

		ConcurrentKafkaListenerContainerFactory<String, OrderCompletedEvent> factory =
				config.popularMenuKafkaListenerContainerFactory(consumerFactory, properties);
		ConcurrentMessageListenerContainer<String, OrderCompletedEvent> container =
				factory.createContainer(OrderCompletedKafkaTopicConfig.ORDER_COMPLETED_TOPIC);

		assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(AckMode.MANUAL_IMMEDIATE);
		assertThat(factory.getContainerProperties().getKafkaConsumerProperties())
				.containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		assertThat(container.getConcurrency()).isEqualTo(3);
	}

	@Test
	void popularMenuKafkaConsumerProperties_rejectsConcurrencyAbovePartitionCount() {
		PopularMenuKafkaConsumerProperties properties = new PopularMenuKafkaConsumerProperties();
		properties.setConcurrency(PopularMenuKafkaConsumerProperties.MAX_CONCURRENCY + 1);

		assertThat(Validation.buildDefaultValidatorFactory().getValidator().validate(properties))
				.extracting(violation -> violation.getPropertyPath().toString())
				.contains("concurrency");
	}

}
