package com.coffeepointordersystem.infra.redis;

import com.coffeepointordersystem.domain.menu.exception.PopularMenuUnavailableException;
import com.coffeepointordersystem.domain.menu.port.PopularMenuCache;
import com.coffeepointordersystem.domain.menu.port.PopularMenuCompletedOrder;
import com.coffeepointordersystem.domain.menu.port.PopularMenuRecordingResult;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

@Component
@SuppressWarnings("rawtypes")
public class RedisPopularMenuCache implements PopularMenuCache {

	private static final String KEY_PREFIX = "popular:menu:";
	private static final String STATE_KEY_PREFIX = "popular:menu:state:";
	private static final String PROCESSED_KEY_PREFIX = "popular:menu:processed:";
	private static final String REBUILDING_KEY = "popular:menu:rebuilding";
	private static final String READY_STATE = "READY";
	private static final String EMPTY_STATE = "EMPTY";
	private static final String UNAVAILABLE_RESULT = "UNAVAILABLE";
	private static final long KEY_EXPIRATION_OFFSET_DAYS = 8L;
	/*
	 * 재구성 표식은 owner token으로 해제 권한을 구분한다. 유한 TTL은 재구성 프로세스 중단 뒤
	 * 모든 요청이 fallback 상태에 고정되는 것을 막고, 재구성 Lua 실행마다 연장한다.
	 */
	private static final long REBUILD_MARKER_EXPIRATION_MILLIS = 60_000L;
	private static final long PROCESSED_RESULT = 1L;
	private static final long DUPLICATE_RESULT = 0L;
	private static final long EXPIRED_RESULT = -1L;
	private static final long REBUILDING_RESULT = -2L;
	/*
	 * Redis Lua는 오류 발생 시 앞선 명령을 자동으로 rollback하지 않는다. 점수 증가가 실패하면
	 * 이 호출이 만든 표식을 제거해 재전달이 다시 처리할 수 있게 한다.
	 */
	private static final RedisScript<Long> RECORD_ORDER_SCRIPT = new DefaultRedisScript<>(
			"""
					if redis.call('EXISTS', KEYS[1]) == 1 then
						return -2
					end

					local currentTime = redis.call('TIME')
					local currentEpochMillis = currentTime[1] * 1000 + math.floor(currentTime[2] / 1000)
					if currentEpochMillis >= tonumber(ARGV[1]) then
						return -1
					end

					local processed = redis.call('SET', KEYS[2], '1', 'NX', 'PXAT', ARGV[1])
					if not processed then
						return 0
					end

					local incremented = redis.pcall('ZINCRBY', KEYS[3], 1, ARGV[2])
					if type(incremented) == 'table' and incremented.err then
						redis.call('DEL', KEYS[2])
						return redis.error_reply(incremented.err)
					end

					local expired = redis.pcall('PEXPIREAT', KEYS[3], ARGV[1])
					if type(expired) == 'table' and expired.err then
						redis.call('DEL', KEYS[2])
						redis.call('ZINCRBY', KEYS[3], -1, ARGV[2])
						return redis.error_reply(expired.err)
					end

					local state = redis.pcall('SET', KEYS[4], 'READY', 'PXAT', ARGV[1])
					if type(state) == 'table' and state.err then
						return redis.error_reply(state.err)
					end

					return 1
					""",
			Long.class
	);
	private static final RedisScript<List> FIND_ORDER_COUNTS_SCRIPT = new DefaultRedisScript<>(
			"""
					if redis.call('EXISTS', KEYS[1]) == 1 then
						return { 'UNAVAILABLE' }
					end

					local result = { 'READY' }
					for keyIndex = 2, #KEYS, 2 do
						local state = redis.call('GET', KEYS[keyIndex])
						local scoreKey = KEYS[keyIndex + 1]
						local scoreExists = redis.call('EXISTS', scoreKey)
						if state == 'EMPTY' and scoreExists == 0 then
						elseif state == 'READY' and scoreExists == 1 then
							local scores = redis.call('ZRANGE', scoreKey, 0, -1, 'WITHSCORES')
							for scoreIndex = 1, #scores, 2 do
								table.insert(result, scores[scoreIndex])
								table.insert(result, scores[scoreIndex + 1])
							end
						else
							return { 'UNAVAILABLE' }
						end
					end

					return result
					""",
			List.class
	);
	private static final RedisScript<Long> REBUILD_SCRIPT = new DefaultRedisScript<>(
			"""
					local function cleanup()
						for keyIndex = 2, #KEYS do
							redis.pcall('DEL', KEYS[keyIndex])
						end
					end

					local function failIfError(result)
						if type(result) == 'table' and result.err then
							cleanup()
							return redis.error_reply(result.err)
						end
						return nil
					end

					if redis.call('GET', KEYS[1]) ~= ARGV[1] then
						return -1
					end

					local markerExpired = redis.pcall('PEXPIRE', KEYS[1], ARGV[2])
					local markerFailure = failIfError(markerExpired)
					if markerFailure then
						return markerFailure
					end

					local dateCount = tonumber(ARGV[3])
					local keyIndex = 2
					local argumentIndex = 4
					for dateIndex = 1, dateCount do
						local scoreKey = KEYS[keyIndex]
						local stateKey = KEYS[keyIndex + 1]
						keyIndex = keyIndex + 2
						local expirationAt = ARGV[argumentIndex]
						argumentIndex = argumentIndex + 1
						local menuCount = tonumber(ARGV[argumentIndex])
						argumentIndex = argumentIndex + 1

						local deleted = redis.pcall('DEL', scoreKey, stateKey)
						local deleteFailure = failIfError(deleted)
						if deleteFailure then
							return deleteFailure
						end

						for menuIndex = 1, menuCount do
							local menuId = ARGV[argumentIndex]
							local orderCount = ARGV[argumentIndex + 1]
							argumentIndex = argumentIndex + 2
							local added = redis.pcall('ZADD', scoreKey, orderCount, menuId)
							local addFailure = failIfError(added)
							if addFailure then
								return addFailure
							end
						end

						local processedCount = tonumber(ARGV[argumentIndex])
						argumentIndex = argumentIndex + 1
						for processedIndex = 1, processedCount do
							local processed = redis.pcall('SET', KEYS[keyIndex], '1', 'PXAT', expirationAt)
							keyIndex = keyIndex + 1
							local processedFailure = failIfError(processed)
							if processedFailure then
								return processedFailure
							end
						end

						if menuCount == 0 then
							local emptyState = redis.pcall('SET', stateKey, 'EMPTY', 'PXAT', expirationAt)
							local emptyStateFailure = failIfError(emptyState)
							if emptyStateFailure then
								return emptyStateFailure
							end
						else
							local scoreExpired = redis.pcall('PEXPIREAT', scoreKey, expirationAt)
							local scoreExpirationFailure = failIfError(scoreExpired)
							if scoreExpirationFailure then
								return scoreExpirationFailure
							end

							local readyState = redis.pcall('SET', stateKey, 'READY', 'PXAT', expirationAt)
							local readyStateFailure = failIfError(readyState)
							if readyStateFailure then
								return readyStateFailure
							end
						end
					end

					return 1
					""",
			Long.class
	);
	private static final RedisScript<Long> RELEASE_REBUILD_SCRIPT = new DefaultRedisScript<>(
			"""
					if redis.call('GET', KEYS[1]) == ARGV[1] then
						return redis.call('DEL', KEYS[1])
					end
					return 0
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
					List.of(REBUILDING_KEY, toProcessedKey(date, orderId), toKey(date), toStateKey(date)),
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
			List<?> result = stringRedisTemplate.execute(FIND_ORDER_COUNTS_SCRIPT, toFindOrderCountKeys(from, to));
			return toOrderCounts(result);
		} catch (DataAccessException exception) {
			throw new PopularMenuUnavailableException();
		}
	}

	@Override
	public boolean tryStartRebuild(String ownerToken) {
		try {
			Boolean started = stringRedisTemplate.opsForValue().setIfAbsent(
					REBUILDING_KEY,
					ownerToken,
					Duration.ofMillis(REBUILD_MARKER_EXPIRATION_MILLIS)
			);
			return Boolean.TRUE.equals(started);
		} catch (DataAccessException exception) {
			throw new PopularMenuUnavailableException();
		}
	}

	@Override
	public void rebuild(
			LocalDate from,
			LocalDate to,
			List<PopularMenuCompletedOrder> completedOrders,
			String ownerToken
	) {
		try {
			List<String> keys = new ArrayList<>();
			List<String> arguments = new ArrayList<>();
			keys.add(REBUILDING_KEY);
			arguments.add(ownerToken);
			arguments.add(Long.toString(REBUILD_MARKER_EXPIRATION_MILLIS));
			arguments.add(Long.toString(toDateCount(from, to)));

			Map<LocalDate, List<PopularMenuCompletedOrder>> ordersByDate = groupOrdersByDate(completedOrders);
			for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1L)) {
				List<PopularMenuCompletedOrder> orders = ordersByDate.getOrDefault(date, List.of());
				Map<Long, Long> orderCounts = countOrdersByMenu(orders);
				keys.add(toKey(date));
				keys.add(toStateKey(date));
				for (PopularMenuCompletedOrder order : orders) {
					keys.add(toProcessedKey(date, order.orderId()));
				}

				arguments.add(Long.toString(toExpirationAt(date).toEpochMilli()));
				arguments.add(Long.toString(orderCounts.size()));
				for (Map.Entry<Long, Long> entry : orderCounts.entrySet()) {
					arguments.add(Long.toString(entry.getKey()));
					arguments.add(Long.toString(entry.getValue()));
				}
				arguments.add(Long.toString(orders.size()));
			}

			Long result = stringRedisTemplate.execute(REBUILD_SCRIPT, keys, arguments.toArray());
			if (result == null || result != PROCESSED_RESULT) {
				throw new PopularMenuUnavailableException();
			}
		} catch (DataAccessException exception) {
			throw new PopularMenuUnavailableException();
		}
	}

	@Override
	public void releaseRebuild(String ownerToken) {
		try {
			stringRedisTemplate.execute(RELEASE_REBUILD_SCRIPT, List.of(REBUILDING_KEY), ownerToken);
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
		if (result == null || result == REBUILDING_RESULT) {
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

	private Map<Long, Long> toOrderCounts(List<?> result) {
		if (result == null || result.isEmpty() || !READY_STATE.equals(result.get(0))) {
			throw new PopularMenuUnavailableException();
		}

		if ((result.size() - 1) % 2 != 0) {
			throw new PopularMenuUnavailableException();
		}

		try {
			Map<Long, Long> orderCounts = new HashMap<>();
			for (int index = 1; index < result.size(); index += 2) {
				long menuId = Long.parseLong((String) result.get(index));
				long orderCount = Long.parseLong((String) result.get(index + 1));
				orderCounts.merge(menuId, orderCount, Math::addExact);
			}

			return orderCounts;
		} catch (ArithmeticException | ClassCastException | NumberFormatException exception) {
			throw new PopularMenuUnavailableException();
		}
	}

	private List<String> toFindOrderCountKeys(LocalDate from, LocalDate to) {
		List<String> keys = new ArrayList<>();
		keys.add(REBUILDING_KEY);
		for (LocalDate date = from; !date.isAfter(to); date = date.plusDays(1L)) {
			keys.add(toStateKey(date));
			keys.add(toKey(date));
		}

		return keys;
	}

	private Map<LocalDate, List<PopularMenuCompletedOrder>> groupOrdersByDate(
			List<PopularMenuCompletedOrder> completedOrders
	) {
		Map<LocalDate, List<PopularMenuCompletedOrder>> ordersByDate = new HashMap<>();
		for (PopularMenuCompletedOrder order : completedOrders) {
			LocalDate date = order.occurredAt().atZone(ZoneOffset.UTC).toLocalDate();
			ordersByDate.computeIfAbsent(date, ignored -> new ArrayList<>()).add(order);
		}

		return ordersByDate;
	}

	private Map<Long, Long> countOrdersByMenu(List<PopularMenuCompletedOrder> orders) {
		Map<Long, Long> orderCounts = new TreeMap<>();
		for (PopularMenuCompletedOrder order : orders) {
			orderCounts.merge(order.menuId(), 1L, Math::addExact);
		}

		return orderCounts;
	}

	private long toDateCount(LocalDate from, LocalDate to) {
		return to.plusDays(1L).toEpochDay() - from.toEpochDay();
	}

	private String toProcessedKey(LocalDate date, long orderId) {
		return PROCESSED_KEY_PREFIX + date + ":" + orderId;
	}

	private String toKey(LocalDate date) {
		return KEY_PREFIX + date;
	}

	private String toStateKey(LocalDate date) {
		return STATE_KEY_PREFIX + date;
	}

}
