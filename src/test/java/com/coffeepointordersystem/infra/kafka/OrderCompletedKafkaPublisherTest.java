package com.coffeepointordersystem.infra.kafka;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.coffeepointordersystem.domain.outbox.event.OrderCompletedOutboxEvent;
import com.coffeepointordersystem.domain.outbox.service.OutboxEventStatusService;
import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

class OrderCompletedKafkaPublisherTest {

	private static final String ORDER_COMPLETED_TOPIC = "order.completed";

	@Test
	void publish_marksOutboxPublishedAfterKafkaPublicationSucceeds() {
		KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate = mock(KafkaTemplate.class);
		OutboxEventStatusService outboxEventStatusService = mock(OutboxEventStatusService.class);
		OrderCompletedKafkaPublisher publisher = new OrderCompletedKafkaPublisher(
				kafkaTemplate,
				outboxEventStatusService
		);
		OrderCompletedOutboxEvent outboxEvent = createOutboxEvent();
		CompletableFuture<SendResult<String, OrderCompletedEvent>> sendResult = CompletableFuture.completedFuture(null);
		when(kafkaTemplate.send(eq(ORDER_COMPLETED_TOPIC), eq("101"), any(OrderCompletedEvent.class)))
				.thenReturn(sendResult);

		publisher.publish(outboxEvent);

		then(outboxEventStatusService).should().markPublished(55L);
	}

	@Test
	void publish_keepsOutboxPendingWhenKafkaPublicationFails() {
		KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate = mock(KafkaTemplate.class);
		OutboxEventStatusService outboxEventStatusService = mock(OutboxEventStatusService.class);
		OrderCompletedKafkaPublisher publisher = new OrderCompletedKafkaPublisher(
				kafkaTemplate,
				outboxEventStatusService
		);
		OrderCompletedOutboxEvent outboxEvent = createOutboxEvent();
		CompletableFuture<SendResult<String, OrderCompletedEvent>> sendResult = CompletableFuture.failedFuture(
				new IllegalStateException("Kafka publish failure")
		);
		when(kafkaTemplate.send(eq(ORDER_COMPLETED_TOPIC), eq("101"), any(OrderCompletedEvent.class)))
				.thenReturn(sendResult);

		publisher.publish(outboxEvent);

		then(outboxEventStatusService).shouldHaveNoInteractions();
	}

	@Test
	void publish_keepsOrderSuccessPathWhenOutboxStatusTransitionFails() {
		KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate = mock(KafkaTemplate.class);
		OutboxEventStatusService outboxEventStatusService = mock(OutboxEventStatusService.class);
		OrderCompletedKafkaPublisher publisher = new OrderCompletedKafkaPublisher(
				kafkaTemplate,
				outboxEventStatusService
		);
		OrderCompletedOutboxEvent outboxEvent = createOutboxEvent();
		CompletableFuture<SendResult<String, OrderCompletedEvent>> sendResult = CompletableFuture.completedFuture(null);
		when(kafkaTemplate.send(eq(ORDER_COMPLETED_TOPIC), eq("101"), any(OrderCompletedEvent.class)))
				.thenReturn(sendResult);
		doThrow(new IllegalStateException("Outbox status transition failure"))
				.when(outboxEventStatusService)
				.markPublished(55L);

		assertThatCode(() -> publisher.publish(outboxEvent)).doesNotThrowAnyException();

		then(outboxEventStatusService).should().markPublished(55L);
	}

	private OrderCompletedOutboxEvent createOutboxEvent() {
		OrderCompletedEvent orderCompletedEvent = new OrderCompletedEvent(
				101L,
				"outbox-test-user",
				1L,
				4_500L,
				Instant.parse("2026-07-16T01:02:03.123456Z")
		);

		return new OrderCompletedOutboxEvent(55L, orderCompletedEvent);
	}

}
