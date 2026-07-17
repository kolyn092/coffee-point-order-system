package com.coffeepointordersystem.infra.kafka;

import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties.AckMode;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class PopularMenuKafkaConsumerConfig {

	private static final long RETRY_INTERVAL_MILLIS = 1_000L;

	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, OrderCompletedEvent>
			popularMenuKafkaListenerContainerFactory(
					ConsumerFactory<String, OrderCompletedEvent> consumerFactory
			) {
		ConcurrentKafkaListenerContainerFactory<String, OrderCompletedEvent> factory =
				new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(consumerFactory);
		factory.getContainerProperties().setAckMode(AckMode.MANUAL_IMMEDIATE);
		factory.getContainerProperties()
				.getKafkaConsumerProperties()
				.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
		factory.setCommonErrorHandler(new DefaultErrorHandler(
				new FixedBackOff(RETRY_INTERVAL_MILLIS, FixedBackOff.UNLIMITED_ATTEMPTS)
		));
		return factory;
	}

}
