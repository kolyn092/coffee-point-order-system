package com.coffeepointordersystem.domain.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffeepointordersystem.domain.menu.exception.PopularMenuUnavailableException;
import com.coffeepointordersystem.domain.menu.port.PopularMenuCache;
import com.coffeepointordersystem.domain.menu.port.PopularMenuRecordingResult;
import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import com.coffeepointordersystem.infra.kafka.PopularMenuKafkaConsumer;
import com.coffeepointordersystem.infra.redis.RedisPopularMenuCache;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.errors.TopicExistsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(PopularMenuIntegrationTest.FixedClockConfig.class)
class PopularMenuIntegrationTest {

	private static final String ORDER_COMPLETED_TOPIC = "order.completed";
	private static final LocalDate TODAY = LocalDate.parse("2026-07-15");
	private static final String REBUILD_USER_ID = "popular-menu-rebuild-user";
	private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.2-alpine");

	@Container
	static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
			.withDatabaseName("coffee_point_order")
			.withUsername("test")
			.withPassword("test");

	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>(REDIS_IMAGE)
			.withExposedPorts(6379);

	@Container
	static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	@Autowired
	private PopularMenuCache popularMenuCache;

	@Autowired
	private PopularMenuKafkaConsumer popularMenuKafkaConsumer;

	@MockitoSpyBean
	private RedisPopularMenuCache redisPopularMenuCache;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
		registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
	}

	@BeforeEach
	void setUp() throws Exception {
		createOrderCompletedTopic();
		insertMenu(3L, "카푸치노", 5500L);
		insertMenu(4L, "바닐라라떼", 6000L);
	}

	@AfterEach
	void cleanUp() {
		stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
		jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", REBUILD_USER_ID);
		jdbcTemplate.update("DELETE FROM point_accounts WHERE user_id = ?", REBUILD_USER_ID);
		jdbcTemplate.update("DELETE FROM menus WHERE id IN (?, ?)", 3L, 4L);
	}

	@Test
	void consumeOrderCompletedEvent_incrementsUtcDateScoreAndSetsExpiration() throws Exception {
		Instant occurredAt = Instant.now().plus(Duration.ofDays(1L));
		LocalDate occurredDate = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();
		String key = "popular:menu:" + occurredDate;
		Instant expectedExpiration = occurredDate.plusDays(8L).atStartOfDay().toInstant(ZoneOffset.UTC);
		initializeCacheWindow(occurredDate);

		kafkaTemplate.send(
				ORDER_COMPLETED_TOPIC,
				"event-1",
				new OrderCompletedEvent(1L, "popular-menu-user", 3L, 5500L, occurredAt)
		).get(10L, TimeUnit.SECONDS);

		assertThat(awaitOrderCount(key, 3L)).isEqualTo(1.0D);

		long remainingSeconds = stringRedisTemplate.getExpire(key);
		long expectedRemainingSeconds = Duration.between(Instant.now(), expectedExpiration).toSeconds();
		assertThat(remainingSeconds).isBetween(expectedRemainingSeconds - 5L, expectedRemainingSeconds + 1L);
	}

	@Test
	void consumeOrderCompletedEvent_retriesAfterRedisScriptFailureWithoutDuplicateScore() throws Exception {
		Instant occurredAt = Instant.now().plus(Duration.ofDays(1L));
		LocalDate occurredDate = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();
		String scoreKey = "popular:menu:" + occurredDate;
		OrderCompletedEvent event = new OrderCompletedEvent(2L, "popular-menu-user", 3L, 5500L, occurredAt);
		initializeCacheWindow(occurredDate);
		doThrow(new PopularMenuUnavailableException())
				.doCallRealMethod()
				.when(redisPopularMenuCache)
				.recordCompletedOrder(event.orderId(), event.menuId(), event.occurredAt());

		kafkaTemplate.send(ORDER_COMPLETED_TOPIC, "event-2", event).get(10L, TimeUnit.SECONDS);

		assertThat(awaitOrderCount(scoreKey, 3L)).isEqualTo(1.0D);
		then(redisPopularMenuCache)
				.should(atLeast(2))
				.recordCompletedOrder(event.orderId(), event.menuId(), event.occurredAt());
	}

	@Test
	void recordCompletedOrder_preventsDuplicateScoreIncreaseAndSetsMatchingExpirations() {
		Instant occurredAt = Instant.now().plus(Duration.ofDays(1L));
		LocalDate occurredDate = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();
		String scoreKey = "popular:menu:" + occurredDate;
		String processedKey = "popular:menu:processed:" + occurredDate + ":101";
		Instant expectedExpiration = occurredDate.plusDays(8L).atStartOfDay().toInstant(ZoneOffset.UTC);
		initializeCacheWindow(occurredDate);

		PopularMenuRecordingResult firstResult = popularMenuCache.recordCompletedOrder(101L, 3L, occurredAt);
		PopularMenuRecordingResult secondResult = popularMenuCache.recordCompletedOrder(101L, 3L, occurredAt);

		assertThat(firstResult).isEqualTo(PopularMenuRecordingResult.PROCESSED);
		assertThat(secondResult).isEqualTo(PopularMenuRecordingResult.DUPLICATE);
		assertThat(stringRedisTemplate.opsForZSet().score(scoreKey, "3")).isEqualTo(1.0D);
		assertThat(stringRedisTemplate.getExpire(scoreKey))
				.isEqualTo(stringRedisTemplate.getExpire(processedKey));
		assertThat(stringRedisTemplate.getExpire(scoreKey))
				.isBetween(
						Duration.between(Instant.now(), expectedExpiration).toSeconds() - 5L,
						Duration.between(Instant.now(), expectedExpiration).toSeconds() + 1L
				);
	}

	@Test
	void recordCompletedOrder_skipsEventOutsideSevenDayAggregationWindow() {
		Instant occurredAt = TODAY.minusDays(8L).atStartOfDay().toInstant(ZoneOffset.UTC);
		LocalDate occurredDate = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();
		String scoreKey = "popular:menu:" + occurredDate;
		String processedKey = "popular:menu:processed:" + occurredDate + ":102";

		PopularMenuRecordingResult result = popularMenuCache.recordCompletedOrder(102L, 3L, occurredAt);

		assertThat(result).isEqualTo(PopularMenuRecordingResult.EXPIRED);
		assertThat(stringRedisTemplate.hasKey(scoreKey)).isFalse();
		assertThat(stringRedisTemplate.hasKey(processedKey)).isFalse();
	}

	@Test
	void recordCompletedOrder_processesConcurrentSameOrderOnce() throws Exception {
		Instant occurredAt = Instant.now().plus(Duration.ofDays(1L));
		LocalDate occurredDate = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();
		String scoreKey = "popular:menu:" + occurredDate;
		initializeCacheWindow(occurredDate);
		ExecutorService executorService = Executors.newFixedThreadPool(10);
		CountDownLatch ready = new CountDownLatch(10);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<PopularMenuRecordingResult>> results = new ArrayList<>();

		try {
			for (int index = 0; index < 10; index++) {
				results.add(executorService.submit(() -> {
					ready.countDown();
					start.await(10L, TimeUnit.SECONDS);
					return popularMenuCache.recordCompletedOrder(103L, 3L, occurredAt);
				}));
			}

			assertThat(ready.await(10L, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			long processedCount = 0L;
			for (Future<PopularMenuRecordingResult> result : results) {
				if (result.get(10L, TimeUnit.SECONDS) == PopularMenuRecordingResult.PROCESSED) {
					processedCount++;
				}
			}

			assertThat(processedCount).isEqualTo(1L);
			assertThat(stringRedisTemplate.opsForZSet().score(scoreKey, "3")).isEqualTo(1.0D);
		} finally {
			executorService.shutdownNow();
		}
	}

	@Test
	void getPopularMenus_aggregatesSevenDaysAndReturnsTopThreeWithMenuIdTieBreak() throws Exception {
		initializeCacheWindow();
		addOrderCount(TODAY.minusDays(7L), 4L, 100L);
		addOrderCount(TODAY.minusDays(6L), 1L, 5L);
		addOrderCount(TODAY, 1L, 3L);
		addOrderCount(TODAY, 2L, 8L);
		addOrderCount(TODAY, 3L, 7L);
		addOrderCount(TODAY, 4L, 6L);

		mockMvc.perform(get("/api/v1/menus/popular"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data[0].menuId").value(1L))
				.andExpect(jsonPath("$.data[0].orderCount").value(8L))
				.andExpect(jsonPath("$.data[1].menuId").value(2L))
				.andExpect(jsonPath("$.data[1].orderCount").value(8L))
				.andExpect(jsonPath("$.data[2].menuId").value(3L))
				.andExpect(jsonPath("$.data[2].name").value("카푸치노"))
				.andExpect(jsonPath("$.data[2].price").value(5500L))
				.andExpect(jsonPath("$.data[2].orderCount").value(7L))
				.andExpect(jsonPath("$.data").isArray())
				.andExpect(jsonPath("$.data").value(org.hamcrest.Matchers.hasSize(3)));
	}

	@Test
	void getPopularMenus_fallsBackToMySqlAndRebuildsIncompleteRedisCache() throws Exception {
		insertPointAccount(REBUILD_USER_ID, 20_000L);
		long firstOrderId = insertOrder(REBUILD_USER_ID, 3L, 5500L, TODAY.atTime(3, 4).toInstant(ZoneOffset.UTC));
		insertOrder(REBUILD_USER_ID, 4L, 6000L, TODAY.atTime(5, 6).toInstant(ZoneOffset.UTC));
		insertOrder(REBUILD_USER_ID, 3L, 5500L, TODAY.atTime(7, 8).toInstant(ZoneOffset.UTC));

		mockMvc.perform(get("/api/v1/menus/popular"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data[0].menuId").value(3L))
				.andExpect(jsonPath("$.data[0].orderCount").value(2L))
				.andExpect(jsonPath("$.data[1].menuId").value(4L))
				.andExpect(jsonPath("$.data[1].orderCount").value(1L));

		assertThat(stringRedisTemplate.opsForZSet().score("popular:menu:" + TODAY, "3")).isEqualTo(2.0D);
		assertThat(stringRedisTemplate.opsForValue().get("popular:menu:state:" + TODAY)).isEqualTo("READY");
		assertThat(stringRedisTemplate.hasKey("popular:menu:processed:" + TODAY + ":" + firstOrderId)).isTrue();
		assertThat(stringRedisTemplate.opsForValue().get("popular:menu:state:" + TODAY.minusDays(1L)))
				.isEqualTo("EMPTY");
		assertThat(stringRedisTemplate.hasKey("popular:menu:rebuilding")).isFalse();
	}

	@Test
	void recordCompletedOrder_defersConsumptionWhileCacheIsRebuilding() {
		Instant occurredAt = TODAY.atTime(3, 4).toInstant(ZoneOffset.UTC);
		initializeCacheWindow();

		assertThat(popularMenuCache.tryStartRebuild("rebuild-owner")).isTrue();
		assertThatThrownBy(() -> popularMenuCache.recordCompletedOrder(104L, 3L, occurredAt))
				.isInstanceOf(PopularMenuUnavailableException.class);

		popularMenuCache.releaseRebuild("rebuild-owner");

		assertThat(popularMenuCache.recordCompletedOrder(104L, 3L, occurredAt))
				.isEqualTo(PopularMenuRecordingResult.PROCESSED);
	}

	@Test
	void consumeOrderCompletedEvent_doesNotAcknowledgeIncompleteCacheAndRebuildsFromMySql() throws Exception {
		Instant occurredAt = TODAY.atTime(3, 4).toInstant(ZoneOffset.UTC);
		Instant eventOccurredAt = occurredAt.plus(Duration.ofHours(2L));
		String scoreKey = "popular:menu:" + TODAY;
		Acknowledgment acknowledgment = mock(Acknowledgment.class);
		insertPointAccount(REBUILD_USER_ID, 20_000L);
		insertOrder(REBUILD_USER_ID, 3L, 5500L, occurredAt);
		insertOrder(REBUILD_USER_ID, 3L, 5500L, occurredAt.plus(Duration.ofHours(1L)));
		long orderId = insertOrder(REBUILD_USER_ID, 3L, 5500L, eventOccurredAt);
		OrderCompletedEvent event = new OrderCompletedEvent(orderId, REBUILD_USER_ID, 3L, 5500L, eventOccurredAt);
		initializeCacheWindow();
		addOrderCount(TODAY, 3L, 2L);
		assertThat(stringRedisTemplate.opsForValue().get("popular:menu:state:" + TODAY)).isEqualTo("READY");
		assertThat(stringRedisTemplate.delete(scoreKey)).isTrue();

		assertThatThrownBy(() -> popularMenuKafkaConsumer.consume(event, acknowledgment))
				.isInstanceOf(PopularMenuUnavailableException.class);
		then(acknowledgment).shouldHaveNoInteractions();

		mockMvc.perform(get("/api/v1/menus/popular"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data[0].menuId").value(3L))
				.andExpect(jsonPath("$.data[0].orderCount").value(3L));

		assertThat(stringRedisTemplate.opsForZSet().score(scoreKey, "3")).isEqualTo(3.0D);
		Acknowledgment retriedAcknowledgment = mock(Acknowledgment.class);
		popularMenuKafkaConsumer.consume(event, retriedAcknowledgment);
		then(retriedAcknowledgment).should().acknowledge();
		assertThat(stringRedisTemplate.opsForZSet().score(scoreKey, "3")).isEqualTo(3.0D);
	}

	@Test
	void consumeOrderCompletedEvent_doesNotAcknowledgeMissingCacheStateAndRebuildsFromMySql() throws Exception {
		Instant occurredAt = TODAY.atTime(3, 4).toInstant(ZoneOffset.UTC);
		Instant eventOccurredAt = occurredAt.plus(Duration.ofHours(2L));
		String scoreKey = "popular:menu:" + TODAY;
		String stateKey = "popular:menu:state:" + TODAY;
		Acknowledgment acknowledgment = mock(Acknowledgment.class);
		insertPointAccount(REBUILD_USER_ID, 20_000L);
		insertOrder(REBUILD_USER_ID, 3L, 5500L, occurredAt);
		insertOrder(REBUILD_USER_ID, 3L, 5500L, occurredAt.plus(Duration.ofHours(1L)));
		long orderId = insertOrder(REBUILD_USER_ID, 3L, 5500L, eventOccurredAt);
		OrderCompletedEvent event = new OrderCompletedEvent(orderId, REBUILD_USER_ID, 3L, 5500L, eventOccurredAt);
		initializeCacheWindow();
		addOrderCount(TODAY, 3L, 2L);
		assertThat(stringRedisTemplate.delete(List.of(scoreKey, stateKey))).isEqualTo(2L);

		assertThatThrownBy(() -> popularMenuKafkaConsumer.consume(event, acknowledgment))
				.isInstanceOf(PopularMenuUnavailableException.class);
		then(acknowledgment).shouldHaveNoInteractions();

		mockMvc.perform(get("/api/v1/menus/popular"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data[0].menuId").value(3L))
				.andExpect(jsonPath("$.data[0].orderCount").value(3L));

		assertThat(stringRedisTemplate.opsForZSet().score(scoreKey, "3")).isEqualTo(3.0D);
		Acknowledgment retriedAcknowledgment = mock(Acknowledgment.class);
		popularMenuKafkaConsumer.consume(event, retriedAcknowledgment);
		then(retriedAcknowledgment).should().acknowledge();
		assertThat(stringRedisTemplate.opsForZSet().score(scoreKey, "3")).isEqualTo(3.0D);
	}

	@Test
	void getPopularMenus_returnsEmptyArrayWhenNoAggregateExists() throws Exception {
		mockMvc.perform(get("/api/v1/menus/popular"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data").isArray())
				.andExpect(jsonPath("$.data").isEmpty());
	}

	private void addOrderCount(LocalDate date, long menuId, long orderCount) {
		stringRedisTemplate.opsForZSet().add("popular:menu:" + date, Long.toString(menuId), orderCount);
		stringRedisTemplate.opsForValue().set("popular:menu:state:" + date, "READY");
	}

	private void initializeCacheWindow() {
		initializeCacheWindow(TODAY);
	}

	private void initializeCacheWindow(LocalDate to) {
		for (LocalDate date = to.minusDays(6L); !date.isAfter(to); date = date.plusDays(1L)) {
			stringRedisTemplate.opsForValue().set("popular:menu:state:" + date, "EMPTY");
		}
	}

	private Double awaitOrderCount(String key, long menuId) {
		Instant deadline = Instant.now().plusSeconds(10L);
		Double orderCount = null;
		while (Instant.now().isBefore(deadline)) {
			orderCount = stringRedisTemplate.opsForZSet().score(key, Long.toString(menuId));
			if (orderCount != null) {
				return orderCount;
			}

			Thread.onSpinWait();
		}

		assertThat(orderCount).as("Kafka 소비 후 Redis 점수가 반영되어야 합니다.").isNotNull();
		return orderCount;
	}

	private void createOrderCompletedTopic() throws Exception {
		try (AdminClient adminClient = AdminClient.create(Map.of(
				AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
				KAFKA.getBootstrapServers()
		))) {
			try {
				adminClient.createTopics(List.of(new NewTopic(ORDER_COMPLETED_TOPIC, 1, (short) 1)))
						.all()
						.get(10L, TimeUnit.SECONDS);
			} catch (ExecutionException exception) {
				if (!(exception.getCause() instanceof TopicExistsException)) {
					throw exception;
				}
			}
		}
	}

	private void insertMenu(long menuId, String name, long price) {
		jdbcTemplate.update(
				"INSERT INTO menus (id, name, price) VALUES (?, ?, ?)",
				menuId,
				name,
				price
		);
	}

	private void insertPointAccount(String userId, long balance) {
		jdbcTemplate.update(
				"INSERT INTO point_accounts (user_id, balance) VALUES (?, ?)",
				userId,
				balance
		);
	}

	private long insertOrder(String userId, long menuId, long paidAmount, Instant orderedAt) {
		jdbcTemplate.update(
				"INSERT INTO orders (user_id, menu_id, paid_amount, ordered_at) VALUES (?, ?, ?, ?)",
				userId,
				menuId,
				paidAmount,
				orderedAt
		);
		return jdbcTemplate.queryForObject(
				"SELECT id FROM orders WHERE user_id = ? AND ordered_at = ?",
				Long.class,
				userId,
				orderedAt
		);
	}

	@TestConfiguration
	static class FixedClockConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
		}

	}

}
