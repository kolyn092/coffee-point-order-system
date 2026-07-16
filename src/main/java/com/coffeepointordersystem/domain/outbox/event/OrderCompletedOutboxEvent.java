package com.coffeepointordersystem.domain.outbox.event;

import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;

public record OrderCompletedOutboxEvent(
		long outboxEventId,
		OrderCompletedEvent orderCompletedEvent
) {

}
