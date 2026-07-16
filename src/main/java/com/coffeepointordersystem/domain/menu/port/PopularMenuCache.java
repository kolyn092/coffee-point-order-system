package com.coffeepointordersystem.domain.menu.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

public interface PopularMenuCache {

	void incrementOrderCount(long menuId, Instant occurredAt);

	Map<Long, Long> findOrderCounts(LocalDate from, LocalDate to);

}
