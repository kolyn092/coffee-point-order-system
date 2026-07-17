package com.coffeepointordersystem.infra.kafka;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.coffeepointordersystem.domain.menu.exception.PopularMenuUnavailableException;
import com.coffeepointordersystem.domain.menu.port.PopularMenuCache;
import com.coffeepointordersystem.domain.menu.port.PopularMenuRecordingResult;
import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

class PopularMenuKafkaConsumerTest {

	private static final OrderCompletedEvent EVENT = new OrderCompletedEvent(
			101L,
			"popular-menu-test-user",
			3L,
			5_500L,
			Instant.parse("2026-07-15T03:04:05Z")
	);

	@Test
	void consume_acknowledgesWhenOrderIsRecorded() {
		PopularMenuCache popularMenuCache = mock(PopularMenuCache.class);
		Acknowledgment acknowledgment = mock(Acknowledgment.class);
		PopularMenuKafkaConsumer consumer = new PopularMenuKafkaConsumer(popularMenuCache);
		when(popularMenuCache.recordCompletedOrder(EVENT.orderId(), EVENT.menuId(), EVENT.occurredAt()))
				.thenReturn(PopularMenuRecordingResult.PROCESSED);

		consumer.consume(EVENT, acknowledgment);

		then(popularMenuCache).should().recordCompletedOrder(EVENT.orderId(), EVENT.menuId(), EVENT.occurredAt());
		then(acknowledgment).should().acknowledge();
	}

	@Test
	void consume_acknowledgesWhenOrderIsDuplicate() {
		PopularMenuCache popularMenuCache = mock(PopularMenuCache.class);
		Acknowledgment acknowledgment = mock(Acknowledgment.class);
		PopularMenuKafkaConsumer consumer = new PopularMenuKafkaConsumer(popularMenuCache);
		when(popularMenuCache.recordCompletedOrder(EVENT.orderId(), EVENT.menuId(), EVENT.occurredAt()))
				.thenReturn(PopularMenuRecordingResult.DUPLICATE);

		consumer.consume(EVENT, acknowledgment);

		then(acknowledgment).should().acknowledge();
	}

	@Test
	void consume_acknowledgesWhenOrderIsExpired() {
		PopularMenuCache popularMenuCache = mock(PopularMenuCache.class);
		Acknowledgment acknowledgment = mock(Acknowledgment.class);
		PopularMenuKafkaConsumer consumer = new PopularMenuKafkaConsumer(popularMenuCache);
		when(popularMenuCache.recordCompletedOrder(EVENT.orderId(), EVENT.menuId(), EVENT.occurredAt()))
				.thenReturn(PopularMenuRecordingResult.EXPIRED);

		consumer.consume(EVENT, acknowledgment);

		then(acknowledgment).should().acknowledge();
	}

	@Test
	void consume_doesNotAcknowledgeWhenRedisRecordingFails() {
		PopularMenuCache popularMenuCache = mock(PopularMenuCache.class);
		Acknowledgment acknowledgment = mock(Acknowledgment.class);
		PopularMenuKafkaConsumer consumer = new PopularMenuKafkaConsumer(popularMenuCache);
		doThrow(new PopularMenuUnavailableException())
				.when(popularMenuCache)
				.recordCompletedOrder(EVENT.orderId(), EVENT.menuId(), EVENT.occurredAt());

		assertThatThrownBy(() -> consumer.consume(EVENT, acknowledgment))
				.isInstanceOf(PopularMenuUnavailableException.class);

		then(acknowledgment).shouldHaveNoInteractions();
	}

}
