package com.coffeepointordersystem.domain.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import com.coffeepointordersystem.domain.outbox.entity.OutboxEvent;
import com.coffeepointordersystem.domain.outbox.entity.OutboxEventStatus;
import com.coffeepointordersystem.domain.outbox.port.OrderCompletedEventPublisher;
import com.coffeepointordersystem.domain.outbox.repository.OutboxEventRepository;
import com.coffeepointordersystem.global.config.OutboxRetryProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class OutboxEventRetryServiceTest {

	private static final String PAYLOAD = "{\"orderId\":101}";
	private static final Instant PUBLISHED_AT = Instant.parse("2026-07-17T01:02:03.123456Z");

	@Test
	void publishNextPendingEvent_returnsFalseWhenPendingEventDoesNotExist() {
		OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
		OrderCompletedEventPublisher orderCompletedEventPublisher = mock(OrderCompletedEventPublisher.class);
		when(outboxEventRepository.findFirstPendingForUpdate()).thenReturn(Optional.empty());
		OutboxEventRetryService outboxEventRetryService = createService(
				outboxEventRepository,
				orderCompletedEventPublisher,
				mock(JsonMapper.class)
		);

		boolean processed = outboxEventRetryService.publishNextPendingEvent();

		assertThat(processed).isFalse();
		then(orderCompletedEventPublisher).shouldHaveNoInteractions();
	}

	@Test
	void publishNextPendingEvent_publishesStoredPayloadAndMarksEventPublished() throws Exception {
		OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
		OrderCompletedEventPublisher orderCompletedEventPublisher = mock(OrderCompletedEventPublisher.class);
		JsonMapper jsonMapper = mock(JsonMapper.class);
		OutboxEvent outboxEvent = pendingOutboxEvent();
		OrderCompletedEvent orderCompletedEvent = orderCompletedEvent();
		when(outboxEventRepository.findFirstPendingForUpdate()).thenReturn(Optional.of(outboxEvent));
		when(jsonMapper.readValue(PAYLOAD, OrderCompletedEvent.class)).thenReturn(orderCompletedEvent);
		OutboxEventRetryService outboxEventRetryService = createService(
				outboxEventRepository,
				orderCompletedEventPublisher,
				jsonMapper
		);

		boolean processed = outboxEventRetryService.publishNextPendingEvent();

		assertThat(processed).isTrue();
		assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
		assertThat(outboxEvent.getPublishedAt()).isEqualTo(PUBLISHED_AT);
		then(orderCompletedEventPublisher).should().publish(orderCompletedEvent, Duration.ofSeconds(5));
	}

	@Test
	void publishNextPendingEvent_skipsEventWhenStatusIsNoLongerPending() {
		OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
		OrderCompletedEventPublisher orderCompletedEventPublisher = mock(OrderCompletedEventPublisher.class);
		OutboxEvent outboxEvent = pendingOutboxEvent();
		outboxEvent.markPublished(PUBLISHED_AT);
		when(outboxEventRepository.findFirstPendingForUpdate()).thenReturn(Optional.of(outboxEvent));
		OutboxEventRetryService outboxEventRetryService = createService(
				outboxEventRepository,
				orderCompletedEventPublisher,
				mock(JsonMapper.class)
		);

		boolean processed = outboxEventRetryService.publishNextPendingEvent();

		assertThat(processed).isTrue();
		then(orderCompletedEventPublisher).shouldHaveNoInteractions();
	}

	@Test
	void publishNextPendingEvent_keepsEventPendingWhenKafkaPublicationFails() throws Exception {
		OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
		OrderCompletedEventPublisher orderCompletedEventPublisher = mock(OrderCompletedEventPublisher.class);
		JsonMapper jsonMapper = mock(JsonMapper.class);
		OutboxEvent outboxEvent = pendingOutboxEvent();
		when(outboxEventRepository.findFirstPendingForUpdate()).thenReturn(Optional.of(outboxEvent));
		when(jsonMapper.readValue(PAYLOAD, OrderCompletedEvent.class)).thenReturn(orderCompletedEvent());
		doThrow(new IllegalStateException("Kafka publish failure"))
				.when(orderCompletedEventPublisher)
				.publish(any(OrderCompletedEvent.class), any(Duration.class));
		OutboxEventRetryService outboxEventRetryService = createService(
				outboxEventRepository,
				orderCompletedEventPublisher,
				jsonMapper
		);

		assertThatThrownBy(outboxEventRetryService::publishNextPendingEvent)
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Kafka publish failure");

		assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(outboxEvent.getPublishedAt()).isNull();
	}

	@Test
	void publishNextPendingEvent_keepsEventPendingWhenStatusTransitionFails() throws Exception {
		OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
		OrderCompletedEventPublisher orderCompletedEventPublisher = mock(OrderCompletedEventPublisher.class);
		JsonMapper jsonMapper = mock(JsonMapper.class);
		OutboxEvent outboxEvent = spy(pendingOutboxEvent());
		when(outboxEventRepository.findFirstPendingForUpdate()).thenReturn(Optional.of(outboxEvent));
		when(jsonMapper.readValue(PAYLOAD, OrderCompletedEvent.class)).thenReturn(orderCompletedEvent());
		doThrow(new IllegalStateException("Outbox status transition failure"))
				.when(outboxEvent)
				.markPublished(eq(PUBLISHED_AT));
		OutboxEventRetryService outboxEventRetryService = createService(
				outboxEventRepository,
				orderCompletedEventPublisher,
				jsonMapper
		);

		assertThatThrownBy(outboxEventRetryService::publishNextPendingEvent)
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Outbox status transition failure");

		assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(outboxEvent.getPublishedAt()).isNull();
		then(orderCompletedEventPublisher).should().publish(any(OrderCompletedEvent.class), any(Duration.class));
	}

	private OutboxEventRetryService createService(
			OutboxEventRepository outboxEventRepository,
			OrderCompletedEventPublisher orderCompletedEventPublisher,
			JsonMapper jsonMapper
	) {
		return new OutboxEventRetryService(
				outboxEventRepository,
				orderCompletedEventPublisher,
				new OutboxRetryProperties(5_000L),
				jsonMapper,
				Clock.fixed(PUBLISHED_AT, ZoneOffset.UTC)
		);
	}

	private OutboxEvent pendingOutboxEvent() {
		return OutboxEvent.pending(
				101L,
				PAYLOAD,
				Instant.parse("2026-07-17T01:00:00Z")
		);
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
