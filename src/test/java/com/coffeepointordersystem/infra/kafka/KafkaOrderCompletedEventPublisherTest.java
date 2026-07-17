package com.coffeepointordersystem.infra.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class KafkaOrderCompletedEventPublisherTest {

	private static final String ORDER_COMPLETED_TOPIC = "order.completed";

	@Test
	void publish_waitsForKafkaPublicationCompletion() {
		KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate = mock(KafkaTemplate.class);
		KafkaOrderCompletedEventPublisher publisher = new KafkaOrderCompletedEventPublisher(kafkaTemplate);
		OrderCompletedEvent orderCompletedEvent = orderCompletedEvent();
		CompletableFuture<SendResult<String, OrderCompletedEvent>> sendResult = CompletableFuture.completedFuture(null);
		when(kafkaTemplate.send(eq(ORDER_COMPLETED_TOPIC), eq("101"), any(OrderCompletedEvent.class)))
				.thenReturn(sendResult);

		publisher.publish(orderCompletedEvent, Duration.ofSeconds(5));

		then(kafkaTemplate).should().send(ORDER_COMPLETED_TOPIC, "101", orderCompletedEvent);
	}

	@Test
	void publish_throwsWhenKafkaPublicationFails() {
		KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate = mock(KafkaTemplate.class);
		KafkaOrderCompletedEventPublisher publisher = new KafkaOrderCompletedEventPublisher(kafkaTemplate);
		CompletableFuture<SendResult<String, OrderCompletedEvent>> sendResult = CompletableFuture.failedFuture(
				new IllegalStateException("Kafka publish failure")
		);
		when(kafkaTemplate.send(eq(ORDER_COMPLETED_TOPIC), eq("101"), any(OrderCompletedEvent.class)))
				.thenReturn(sendResult);

		assertThatThrownBy(() -> publisher.publish(orderCompletedEvent(), Duration.ofSeconds(5)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Kafka 발행에 실패했거나 제한 시간 안에 완료되지 않았습니다.");
	}

	@Test
	void publish_throwsWhenKafkaPublicationTimesOut() {
		KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate = mock(KafkaTemplate.class);
		KafkaOrderCompletedEventPublisher publisher = new KafkaOrderCompletedEventPublisher(kafkaTemplate);
		CompletableFuture<SendResult<String, OrderCompletedEvent>> sendResult = new CompletableFuture<>();
		when(kafkaTemplate.send(eq(ORDER_COMPLETED_TOPIC), eq("101"), any(OrderCompletedEvent.class)))
				.thenReturn(sendResult);

		assertThatThrownBy(() -> publisher.publish(orderCompletedEvent(), Duration.ofMillis(1)))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Kafka 발행에 실패했거나 제한 시간 안에 완료되지 않았습니다.");
	}

	private OrderCompletedEvent orderCompletedEvent() {
		return new OrderCompletedEvent(
				101L,
				"outbox-retry-test-user",
				1L,
				4_500L,
				Instant.parse("2026-07-17T01:00:00Z")
		);
	}

}
