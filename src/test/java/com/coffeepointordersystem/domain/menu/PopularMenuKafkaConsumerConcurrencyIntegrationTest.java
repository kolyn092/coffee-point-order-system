package com.coffeepointordersystem.domain.menu;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import com.coffeepointordersystem.infra.kafka.OrderCompletedKafkaTopicConfig;
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
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest(properties = "popular-menu.consumer.concurrency=3")
class PopularMenuKafkaConsumerConcurrencyIntegrationTest {

	private static final String ORDER_COMPLETED_TOPIC = "order.completed";
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
	private KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate;

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
	}

	@AfterEach
	void cleanUp() {
		stringRedisTemplate.execute((RedisCallback<Void>) connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
	}

	@Test
	void consumeSameOrderCompletedEventFromThreePartitions_incrementsRedisScoreOnce() throws Exception {
		Instant occurredAt = Instant.now().plus(Duration.ofDays(1L));
		LocalDate occurredDate = occurredAt.atZone(ZoneOffset.UTC).toLocalDate();
		String scoreKey = "popular:menu:" + occurredDate;
		OrderCompletedEvent event = new OrderCompletedEvent(
				105L,
				"popular-menu-user",
				3L,
				5_500L,
				occurredAt
		);
		initializeCacheWindow(occurredDate);

		for (int partition = 0; partition < OrderCompletedKafkaTopicConfig.PARTITION_COUNT; partition++) {
			kafkaTemplate.send(
					ORDER_COMPLETED_TOPIC,
					partition,
					"same-order-" + partition,
					event
			).get(10L, TimeUnit.SECONDS);
		}

		assertThat(awaitOrderCount(scoreKey, 3L)).isEqualTo(1.0D);
	}

	private void createOrderCompletedTopic() throws Exception {
		try (AdminClient adminClient = AdminClient.create(Map.of(
				AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
				KAFKA.getBootstrapServers()
		))) {
			try {
				adminClient.createTopics(List.of(new NewTopic(
						ORDER_COMPLETED_TOPIC,
						OrderCompletedKafkaTopicConfig.PARTITION_COUNT,
						(short) 1
				)))
						.all()
						.get(10L, TimeUnit.SECONDS);
			} catch (ExecutionException exception) {
				if (!(exception.getCause() instanceof TopicExistsException)) {
					throw exception;
				}
			}
		}
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

}
