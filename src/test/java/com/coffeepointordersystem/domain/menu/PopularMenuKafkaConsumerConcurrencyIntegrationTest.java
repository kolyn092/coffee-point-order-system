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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.MemberDescription;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
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

	private static final Duration ASSIGNMENT_TIMEOUT = Duration.ofSeconds(10L);
	private static final String ORDER_COMPLETED_TOPIC = "order.completed";
	private static final String CONSUMER_GROUP_ID = "popular-menu-concurrency-" + UUID.randomUUID();
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
		registry.add("spring.kafka.consumer.group-id", () -> CONSUMER_GROUP_ID);
		registry.add("spring.kafka.consumer.auto-offset-reset", () -> "latest");
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
		awaitConsumerGroupPartitionAssignment();

		for (int partition = 0; partition < OrderCompletedKafkaTopicConfig.PARTITION_COUNT; partition++) {
			kafkaTemplate.send(
					ORDER_COMPLETED_TOPIC,
					partition,
					"same-order-" + partition,
					event
			).get(10L, TimeUnit.SECONDS);
		}

		awaitConsumerGroupOffsets();
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

	private void awaitConsumerGroupPartitionAssignment() {
		Instant deadline = Instant.now().plus(ASSIGNMENT_TIMEOUT);
		long activeConsumerCount = 0L;
		Set<TopicPartition> assignedPartitions = Set.of();
		Exception lastException = null;

		try (AdminClient adminClient = createAdminClient()) {
			while (Instant.now().isBefore(deadline)) {
				try {
					ConsumerGroupDescription description = adminClient.describeConsumerGroups(List.of(CONSUMER_GROUP_ID))
							.all()
							.get(1L, TimeUnit.SECONDS)
							.get(CONSUMER_GROUP_ID);
					List<MemberDescription> members = List.copyOf(description.members());
					assignedPartitions = members.stream()
							.flatMap(member -> member.assignment().topicPartitions().stream())
							.filter(topicPartition -> topicPartition.topic().equals(ORDER_COMPLETED_TOPIC))
							.collect(java.util.stream.Collectors.toSet());
					activeConsumerCount = members.stream()
							.filter(member -> member.assignment().topicPartitions().stream()
									.anyMatch(topicPartition -> topicPartition.topic().equals(ORDER_COMPLETED_TOPIC)))
							.count();

					if (activeConsumerCount == OrderCompletedKafkaTopicConfig.PARTITION_COUNT
							&& assignedPartitions.size() == OrderCompletedKafkaTopicConfig.PARTITION_COUNT) {
						return;
					}
				} catch (Exception exception) {
					lastException = exception;
				}

				Thread.sleep(100L);
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Kafka consumer group 파티션 할당 대기 중 인터럽트가 발생했습니다.", exception);
		}

		throw new IllegalStateException(
				"Kafka consumer group의 3개 파티션 할당이 제한 시간 안에 완료되지 않았습니다. groupId=%s, "
						+ "활성 consumer 수=%d, 할당 파티션=%s"
						.formatted(CONSUMER_GROUP_ID, activeConsumerCount, assignedPartitions),
				lastException
		);
	}

	private void awaitConsumerGroupOffsets() {
		Instant deadline = Instant.now().plus(ASSIGNMENT_TIMEOUT);
		Map<TopicPartition, OffsetAndMetadata> committedOffsets = Map.of();
		Exception lastException = null;

		try (AdminClient adminClient = createAdminClient()) {
			while (Instant.now().isBefore(deadline)) {
				try {
					committedOffsets = adminClient.listConsumerGroupOffsets(CONSUMER_GROUP_ID)
							.partitionsToOffsetAndMetadata()
							.get(1L, TimeUnit.SECONDS);
					if (hasCommittedOffsetForAllPartitions(committedOffsets)) {
						return;
					}
				} catch (Exception exception) {
					lastException = exception;
				}

				Thread.sleep(100L);
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Kafka consumer group offset 대기 중 인터럽트가 발생했습니다.", exception);
		}

		throw new IllegalStateException(
				"Kafka consumer group의 3개 파티션 이벤트 소비가 제한 시간 안에 완료되지 않았습니다. groupId=%s, "
						+ "commit offset=%s"
						.formatted(CONSUMER_GROUP_ID, committedOffsets),
				lastException
		);
	}

	private boolean hasCommittedOffsetForAllPartitions(Map<TopicPartition, OffsetAndMetadata> committedOffsets) {
		for (int partition = 0; partition < OrderCompletedKafkaTopicConfig.PARTITION_COUNT; partition++) {
			OffsetAndMetadata offset = committedOffsets.get(new TopicPartition(ORDER_COMPLETED_TOPIC, partition));
			if (offset == null || offset.offset() < 1L) {
				return false;
			}
		}

		return true;
	}

	private void initializeCacheWindow(LocalDate to) {
		for (LocalDate date = to.minusDays(6L); !date.isAfter(to); date = date.plusDays(1L)) {
			stringRedisTemplate.opsForValue().set("popular:menu:state:" + date, "EMPTY");
		}
	}

	private AdminClient createAdminClient() {
		return AdminClient.create(Map.of(
				AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
				KAFKA.getBootstrapServers()
		));
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
