package com.coffeepointordersystem.domain.order.dto;

import com.coffeepointordersystem.domain.order.entity.Order;
import com.coffeepointordersystem.domain.point.entity.PointAccount;
import java.time.Instant;

public record OrderResponse(
		long orderId,
		String userId,
		long menuId,
		long paidAmount,
		long remainingPointBalance,
		Instant orderedAt
) {

	public static OrderResponse from(Order order, PointAccount pointAccount) {
		return new OrderResponse(
				order.getId(),
				order.getUserId(),
				order.getMenuId(),
				order.getPaidAmount(),
				pointAccount.getBalance(),
				order.getOrderedAt()
		);
	}

}
