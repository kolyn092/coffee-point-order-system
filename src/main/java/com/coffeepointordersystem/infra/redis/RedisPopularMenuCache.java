package com.coffeepointordersystem.infra.redis;

import com.coffeepointordersystem.domain.menu.exception.PopularMenuUnavailableException;
import com.coffeepointordersystem.domain.menu.port.PopularMenuCache;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.stereotype.Component;

@Component
public class RedisPopularMenuCache implements PopularMenuCache {

	private static final String KEY_PREFIX = "popular:menu:";
	private static final long KEY_EXPIRATION_OFFSET_DAYS = 8L;

	private final StringRedisTemplate stringRedisTemplate;

	public RedisPopularMenuCache(StringRedisTemplate stringRedisTemplate) {
		this.stringRedisTemplate = stringRedisTemplate;
	}

	@Override
	public void incrementOrderCount(long menuId, Instant occurredAt) {
		LocalDate date = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();
		String key = toKey(date);
		Instant expirationAt = date.plusDays(KEY_EXPIRATION_OFFSET_DAYS)
				.atStartOfDay()
				.toInstant(ZoneOffset.UTC);

		try {
			stringRedisTemplate.opsForZSet().incrementScore(key, Long.toString(menuId), 1.0D);
			stringRedisTemplate.expireAt(key, Date.from(expirationAt));
		} catch (DataAccessException exception) {
			throw new PopularMenuUnavailableException();
		}
	}

	@Override
	public Map<Long, Long> findOrderCounts(LocalDate from, LocalDate to) {
		try {
			Map<Long, Long> orderCounts = new HashMap<>();
			for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1L)) {
				addOrderCounts(orderCounts, stringRedisTemplate.opsForZSet().rangeWithScores(toKey(date), 0L, -1L));
			}

			return orderCounts;
		} catch (DataAccessException exception) {
			throw new PopularMenuUnavailableException();
		}
	}

	private void addOrderCounts(Map<Long, Long> orderCounts, Set<TypedTuple<String>> scores) {
		if (scores == null) {
			return;
		}

		for (TypedTuple<String> score : scores) {
			if (score.getValue() == null || score.getScore() == null) {
				continue;
			}

			orderCounts.merge(
					Long.parseLong(score.getValue()),
					score.getScore().longValue(),
					Math::addExact
			);
		}
	}

	private String toKey(LocalDate date) {
		return KEY_PREFIX + date;
	}

}
