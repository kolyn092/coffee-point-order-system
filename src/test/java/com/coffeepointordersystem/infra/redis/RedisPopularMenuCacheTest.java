package com.coffeepointordersystem.infra.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.coffeepointordersystem.domain.menu.exception.PopularMenuUnavailableException;
import com.coffeepointordersystem.domain.menu.port.PopularMenuRecordingResult;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

@SuppressWarnings({"rawtypes", "unchecked"})
class RedisPopularMenuCacheTest {

	private static final Instant OCCURRED_AT = Instant.parse("2026-07-15T03:04:05Z");
	private static final Instant EXPIRATION_AT = Instant.parse("2026-07-23T00:00:00Z");
	private static final long ORDER_ID = 101L;
	private static final long MENU_ID = 3L;

	@Test
	void recordCompletedOrder_runsAtomicScriptWithProcessedAndScoreKeys() {
		StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
		RedisPopularMenuCache cache = new RedisPopularMenuCache(stringRedisTemplate);
		when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);

		PopularMenuRecordingResult result = cache.recordCompletedOrder(ORDER_ID, MENU_ID, OCCURRED_AT);

		ArgumentCaptor<RedisScript> scriptCaptor = ArgumentCaptor.forClass(RedisScript.class);
		then(stringRedisTemplate).should().execute(
				scriptCaptor.capture(),
				eq(eqKeys()),
				eq(Long.toString(EXPIRATION_AT.toEpochMilli())),
				eq(Long.toString(MENU_ID))
		);
		assertThat(result).isEqualTo(PopularMenuRecordingResult.PROCESSED);
		assertThat(((DefaultRedisScript<?>) scriptCaptor.getValue()).getScriptAsString())
				.contains("TIME", "SET", "NX", "PXAT", "ZINCRBY", "PEXPIREAT");
	}

	@Test
	void recordCompletedOrder_returnsDuplicateWhenProcessedKeyAlreadyExists() {
		StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
		RedisPopularMenuCache cache = new RedisPopularMenuCache(stringRedisTemplate);
		when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(0L);

		PopularMenuRecordingResult result = cache.recordCompletedOrder(ORDER_ID, MENU_ID, OCCURRED_AT);

		assertThat(result).isEqualTo(PopularMenuRecordingResult.DUPLICATE);
	}

	@Test
	void recordCompletedOrder_processesWhenRedisScriptAcceptsEventPastApplicationExpiration() {
		StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
		RedisPopularMenuCache cache = new RedisPopularMenuCache(stringRedisTemplate);
		Instant expiredOccurredAt = Instant.parse("2026-07-07T03:04:05Z");
		when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(1L);

		PopularMenuRecordingResult result = cache.recordCompletedOrder(ORDER_ID, MENU_ID, expiredOccurredAt);

		assertThat(result).isEqualTo(PopularMenuRecordingResult.PROCESSED);
	}

	@Test
	void recordCompletedOrder_returnsExpiredWhenRedisScriptReportsExpired() {
		StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
		RedisPopularMenuCache cache = new RedisPopularMenuCache(stringRedisTemplate);
		when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any())).thenReturn(-1L);

		PopularMenuRecordingResult result = cache.recordCompletedOrder(ORDER_ID, MENU_ID, OCCURRED_AT);

		assertThat(result).isEqualTo(PopularMenuRecordingResult.EXPIRED);
	}

	@Test
	void recordCompletedOrder_throwsWhenRedisScriptFails() {
		StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
		RedisPopularMenuCache cache = new RedisPopularMenuCache(stringRedisTemplate);
		when(stringRedisTemplate.execute(any(RedisScript.class), anyList(), any(), any()))
				.thenThrow(new RedisConnectionFailureException("Redis unavailable"));

		assertThatThrownBy(() -> cache.recordCompletedOrder(ORDER_ID, MENU_ID, OCCURRED_AT))
				.isInstanceOf(PopularMenuUnavailableException.class);
	}

	private List<String> eqKeys() {
		return List.of("popular:menu:processed:2026-07-15:101", "popular:menu:2026-07-15");
	}

}
