package com.coffeepointordersystem.infra.redis;

import com.coffeepointordersystem.domain.menu.exception.PopularMenuUnavailableException;
import com.coffeepointordersystem.domain.menu.port.PopularMenuCache;
import com.coffeepointordersystem.domain.menu.port.PopularMenuRecordingResult;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
public class RedisPopularMenuCache implements PopularMenuCache {

	private static final String KEY_PREFIX = "popular:menu:";
	private static final String PROCESSED_KEY_PREFIX = "popular:menu:processed:";
	private static final long KEY_EXPIRATION_OFFSET_DAYS = 8L;
	private static final long PROCESSED_RESULT = 1L;
	private static final long DUPLICATE_RESULT = 0L;
	private static final long EXPIRED_RESULT = -1L;
	/*
	 * Redis Lua는 오류 발생 시 앞선 명령을 자동으로 rollback하지 않는다. 점수 증가가 실패하면
	 * 이 호출이 만든 표식을 제거해 재전달이 다시 처리할 수 있게 한다.
	 */
	private static final RedisScript<Long> RECORD_ORDER_SCRIPT = new DefaultRedisScript<>(
			"""
					local currentTime = redis.call('TIME')
					local currentEpochMillis = currentTime[1] * 1000 + math.floor(currentTime[2] / 1000)
					if currentEpochMillis >= tonumber(ARGV[1]) then
						return -1
					end

					local processed = redis.call('SET', KEYS[1], '1', 'NX', 'PXAT', ARGV[1])
					if not processed then
						return 0
					end

					local incremented = redis.pcall('ZINCRBY', KEYS[2], 1, ARGV[2])
					if type(incremented) == 'table' and incremented.err then
						redis.call('DEL', KEYS[1])
						return redis.error_reply(incremented.err)
					end

					redis.call('PEXPIREAT', KEYS[2], ARGV[1])
					return 1
					""",
			Long.class
	);

	private final StringRedisTemplate stringRedisTemplate;

	public RedisPopularMenuCache(StringRedisTemplate stringRedisTemplate) {
		this.stringRedisTemplate = stringRedisTemplate;
	}

	@Override
	public PopularMenuRecordingResult recordCompletedOrder(long orderId, long menuId, Instant occurredAt) {
		LocalDate date = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();
		Instant expirationAt = toExpirationAt(date);

		try {
			Long result = stringRedisTemplate.execute(
					RECORD_ORDER_SCRIPT,
					List.of(toProcessedKey(date, orderId), toKey(date)),
					Long.toString(expirationAt.toEpochMilli()),
					Long.toString(menuId)
			);
			return toRecordingResult(result);
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

	private Instant toExpirationAt(LocalDate date) {
		return date.plusDays(KEY_EXPIRATION_OFFSET_DAYS)
				.atStartOfDay()
				.toInstant(ZoneOffset.UTC);
	}

	private PopularMenuRecordingResult toRecordingResult(Long result) {
		if (result == null) {
			throw new PopularMenuUnavailableException();
		}

		if (result == PROCESSED_RESULT) {
			return PopularMenuRecordingResult.PROCESSED;
		}

		if (result == DUPLICATE_RESULT) {
			return PopularMenuRecordingResult.DUPLICATE;
		}

		if (result == EXPIRED_RESULT) {
			return PopularMenuRecordingResult.EXPIRED;
		}

		throw new PopularMenuUnavailableException();
	}

	private String toProcessedKey(LocalDate date, long orderId) {
		return PROCESSED_KEY_PREFIX + date + ":" + orderId;
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
