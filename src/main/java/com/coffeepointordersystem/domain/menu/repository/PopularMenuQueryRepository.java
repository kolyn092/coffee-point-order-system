package com.coffeepointordersystem.domain.menu.repository;

import com.coffeepointordersystem.domain.menu.exception.PopularMenuUnavailableException;
import com.coffeepointordersystem.domain.menu.port.PopularMenuCompletedOrder;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PopularMenuQueryRepository {

	private static final String ORDER_COUNT_QUERY = """
			SELECT menu_id, COUNT(*) AS order_count
			FROM orders USE INDEX (idx_orders_ordered_at_menu_id)
			WHERE ordered_at >= ?
			  AND ordered_at < ?
			GROUP BY menu_id
			""";
	private static final String COMPLETED_ORDER_QUERY = """
			SELECT id, menu_id, ordered_at
			FROM orders USE INDEX (idx_orders_ordered_at_menu_id)
			WHERE ordered_at >= ?
			  AND ordered_at < ?
			ORDER BY id
			""";

	private final JdbcTemplate jdbcTemplate;

	public PopularMenuQueryRepository(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	public Map<Long, Long> findOrderCounts(LocalDate from, LocalDate to) {
		try {
			return jdbcTemplate.query(
					ORDER_COUNT_QUERY,
					resultSet -> {
						Map<Long, Long> orderCounts = new HashMap<>();
						while (resultSet.next()) {
							orderCounts.put(resultSet.getLong("menu_id"), resultSet.getLong("order_count"));
						}

						return orderCounts;
					},
					Timestamp.from(toStartOfDay(from)),
					Timestamp.from(toStartOfNextDay(to))
			);
		} catch (DataAccessException exception) {
			throw new PopularMenuUnavailableException();
		}
	}

	public List<PopularMenuCompletedOrder> findCompletedOrders(LocalDate from, LocalDate to) {
		try {
			return jdbcTemplate.query(
					COMPLETED_ORDER_QUERY,
					(resultSet, rowNumber) -> new PopularMenuCompletedOrder(
							resultSet.getLong("id"),
							resultSet.getLong("menu_id"),
							resultSet.getTimestamp("ordered_at").toInstant()
					),
					Timestamp.from(toStartOfDay(from)),
					Timestamp.from(toStartOfNextDay(to))
			);
		} catch (DataAccessException exception) {
			throw new PopularMenuUnavailableException();
		}
	}

	private Instant toStartOfDay(LocalDate date) {
		return date.atStartOfDay().toInstant(ZoneOffset.UTC);
	}

	private Instant toStartOfNextDay(LocalDate date) {
		return date.plusDays(1L).atStartOfDay().toInstant(ZoneOffset.UTC);
	}

}
