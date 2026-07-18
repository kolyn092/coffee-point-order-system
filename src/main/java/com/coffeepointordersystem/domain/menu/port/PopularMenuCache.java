package com.coffeepointordersystem.domain.menu.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

public interface PopularMenuCache {

	PopularMenuRecordingResult recordCompletedOrder(long orderId, long menuId, Instant occurredAt);

	Map<Long, Long> findOrderCounts(LocalDate from, LocalDate to);

	boolean tryStartRebuild(String ownerToken);

	void rebuild(LocalDate from, LocalDate to, List<PopularMenuCompletedOrder> completedOrders, String ownerToken);

	void releaseRebuild(String ownerToken);

}
