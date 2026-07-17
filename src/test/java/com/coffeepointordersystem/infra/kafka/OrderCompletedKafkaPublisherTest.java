package com.coffeepointordersystem.infra.kafka;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.coffeepointordersystem.domain.outbox.event.OrderCompletedOutboxEvent;
import com.coffeepointordersystem.domain.outbox.service.OutboxEventRetryService;
import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class OrderCompletedKafkaPublisherTest {

	@Test
	void publish_delegatesToLockedPublicationService() {
		OutboxEventRetryService outboxEventRetryService = mock(OutboxEventRetryService.class);
		OrderCompletedKafkaPublisher publisher = new OrderCompletedKafkaPublisher(outboxEventRetryService);
		OrderCompletedOutboxEvent outboxEvent = createOutboxEvent();

		publisher.publish(outboxEvent);

		then(outboxEventRetryService).should().publishPendingEvent(55L);
	}

	@Test
	void publish_keepsOrderSuccessPathWhenLockedPublicationFails() {
		OutboxEventRetryService outboxEventRetryService = mock(OutboxEventRetryService.class);
		OrderCompletedKafkaPublisher publisher = new OrderCompletedKafkaPublisher(outboxEventRetryService);
		OrderCompletedOutboxEvent outboxEvent = createOutboxEvent();
		doThrow(new IllegalStateException("Kafka publish failure"))
				.when(outboxEventRetryService)
				.publishPendingEvent(55L);

		assertThatCode(() -> publisher.publish(outboxEvent)).doesNotThrowAnyException();

		then(outboxEventRetryService).should().publishPendingEvent(55L);
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
