package com.coffeepointordersystem.infra.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;

@SuppressWarnings("unchecked")
class PopularMenuKafkaConsumerConfigTest {

	@Test
	void popularMenuKafkaListenerContainerFactory_acknowledgesOnlyAfterListenerCompletion() {
		ConsumerFactory<String, OrderCompletedEvent> consumerFactory = mock(ConsumerFactory.class);
		PopularMenuKafkaConsumerConfig config = new PopularMenuKafkaConsumerConfig();

		ConcurrentKafkaListenerContainerFactory<String, OrderCompletedEvent> factory =
				config.popularMenuKafkaListenerContainerFactory(consumerFactory);

		assertThat(factory.getContainerProperties().getAckMode()).isEqualTo(AckMode.MANUAL_IMMEDIATE);
		assertThat(factory.getContainerProperties().getKafkaConsumerProperties())
				.containsEntry(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
	}

}
