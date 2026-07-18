package com.coffeepointordersystem.domain.menu.port;

import java.time.Instant;

public record PopularMenuCompletedOrder(

		long orderId,
		long menuId,
		Instant occurredAt
) {

}
