package com.coffeepointordersystem.domain.order.event;

import com.coffeepointordersystem.domain.order.entity.Order;
import java.time.Instant;

public record OrderCompletedEvent(
		long orderId,
		String userId,
		long menuId,
		long paidAmount,
		Instant occurredAt
) {

	public static OrderCompletedEvent from(Order order) {
		return new OrderCompletedEvent(
				order.getId(),
				order.getUserId(),
				order.getMenuId(),
				order.getPaidAmount(),
				order.getOrderedAt()
		);
	}

}
