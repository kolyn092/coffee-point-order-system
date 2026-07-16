package com.coffeepointordersystem.domain.menu;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
		jdbcTemplate.update("DELETE FROM menus WHERE id IN (?, ?)", 3L, 4L);
	}

	@Test
	void consumeOrderCompletedEvent_incrementsUtcDateScoreAndSetsExpiration() throws Exception {
		Instant occurredAt = Instant.now().plus(Duration.ofDays(1L));
		LocalDate occurredDate = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();
		String key = "popular:menu:" + occurredDate;
		Instant expectedExpiration = occurredDate.plusDays(8L).atStartOfDay().toInstant(ZoneOffset.UTC);

		kafkaTemplate.send(
				ORDER_COMPLETED_TOPIC,
				"event-1",
				new OrderCompletedEvent(1L, "popular-menu-user", 3L, 5500L, occurredAt)
		).get(10L, TimeUnit.SECONDS);

		assertThat(awaitOrderCount(key, 3L)).isEqualTo(1.0D);

		long remainingSeconds = stringRedisTemplate.getExpire(key, TimeUnit.SECONDS);
		long expectedRemainingSeconds = Duration.between(Instant.now(), expectedExpiration).toSeconds();
		assertThat(remainingSeconds).isBetween(expectedRemainingSeconds - 5L, expectedRemainingSeconds + 1L);
	}

	@Test
	void getPopularMenus_aggregatesSevenDaysAndReturnsTopThreeWithMenuIdTieBreak() throws Exception {
		addOrderCount(TODAY.minusDays(7L), 4L, 100L);
		addOrderCount(TODAY.minusDays(6L), 1L, 5L);
		addOrderCount(TODAY, 1L, 3L);
		addOrderCount(TODAY, 2L, 8L);
		addOrderCount(TODAY, 3L, 7L);
		addOrderCount(TODAY, 4L, 6L);

		mockMvc.perform(get("/api/v1/menus/popular"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].menuId").value(1L))
				.andExpect(jsonPath("$[0].orderCount").value(8L))
				.andExpect(jsonPath("$[1].menuId").value(2L))
				.andExpect(jsonPath("$[1].orderCount").value(8L))
				.andExpect(jsonPath("$[2].menuId").value(3L))
				.andExpect(jsonPath("$[2].orderCount").value(7L))
				.andExpect(jsonPath("$").isArray())
				.andExpect(jsonPath("$").value(org.hamcrest.Matchers.hasSize(3)));
	}

	@Test
	void getPopularMenus_returnsEmptyArrayWhenNoAggregateExists() throws Exception {
		mockMvc.perform(get("/api/v1/menus/popular"))
				.andExpect(status().isOk())
				.andExpect(content().json("[]"));
	}

	private void addOrderCount(LocalDate date, long menuId, long orderCount) {
		stringRedisTemplate.opsForZSet().add("popular:menu:" + date, Long.toString(menuId), orderCount);
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

	@TestConfiguration
	static class FixedClockConfig {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
		}

	}

}
