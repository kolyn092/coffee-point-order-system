package com.coffeepointordersystem.domain.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import com.coffeepointordersystem.domain.outbox.port.OrderCompletedEventPublisher;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
@SpringBootTest
@Import(OutboxEventRetryIntegrationTest.RetryTestConfig.class)
class OutboxEventRetryIntegrationTest {

	private static final String ORDER_COMPLETED_TOPIC = "order.completed";
	private static final String TEST_USER_ID = "outbox-retry-integration-user";
	private static final Instant ORDERED_AT = Instant.parse("2026-07-17T01:00:00.123456Z");
	private static final Instant PUBLISHED_AT = Instant.parse("2026-07-17T01:02:03.123456Z");

	@Container
	static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
			.withDatabaseName("coffee_point_order")
			.withUsername("test")
			.withPassword("test");

	@Container
	static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

	@Autowired
	private ControllableOrderCompletedEventPublisher orderCompletedEventPublisher;

	@Autowired
	private MutableClock clock;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private OutboxEventRetryScheduler outboxEventRetryScheduler;

	@Autowired
	private OutboxEventRetryService outboxEventRetryService;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
		registry.add("spring.kafka.listener.auto-startup", () -> "false");
		registry.add("outbox.retry.fixed-delay", () -> "1h");
	}

	@BeforeEach
	void setUp() throws Exception {
		createOrderCompletedTopic();
		orderCompletedEventPublisher.reset();
		clock.reset();
		insertPendingOutboxEvent();
	}

	@AfterEach
	void cleanUpTestData() {
		jdbcTemplate.update(
				"DELETE outbox_events FROM outbox_events "
						+ "INNER JOIN orders ON outbox_events.order_id = orders.id "
						+ "WHERE orders.user_id = ?",
				TEST_USER_ID
		);
		jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", TEST_USER_ID);
		jdbcTemplate.update("DELETE FROM point_accounts WHERE user_id = ?", TEST_USER_ID);
	}

	@Test
	void publishNextPendingEvent_keepsOutboxPendingWhenKafkaPublicationFails() {
		orderCompletedEventPublisher.failKafkaPublication();

		assertThatThrownBy(outboxEventRetryService::publishNextPendingEvent)
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("Kafka publish failure");

		assertThat(findOutboxStatus()).isEqualTo("PENDING");
		assertThat(findPublishedAt()).isNull();
		assertThat(orderCompletedEventPublisher.getPublishCount()).isZero();
	}

	@Test
	void retryPendingEvents_publishesStoredPayloadAfterKafkaRecovers() throws Exception {
		orderCompletedEventPublisher.failKafkaPublication();

		assertThatThrownBy(outboxEventRetryService::publishNextPendingEvent)
				.isInstanceOf(IllegalStateException.class);

		try (Consumer<String, OrderCompletedEvent> consumer = createConsumer()) {
			consumer.subscribe(List.of(ORDER_COMPLETED_TOPIC));
			awaitPartitionAssignment(consumer);
			orderCompletedEventPublisher.recoverKafkaPublication();

			outboxEventRetryScheduler.retryPendingEvents();

			ConsumerRecord<String, OrderCompletedEvent> record = awaitOrderCompletedEvent(consumer);
			assertThat(record.key()).isEqualTo("101");
			assertThat(record.value()).isEqualTo(orderCompletedEvent());
		}

		assertThat(findOutboxStatus()).isEqualTo("PUBLISHED");
		assertThat(findPublishedAt()).isEqualTo(Timestamp.from(PUBLISHED_AT));
		assertThat(orderCompletedEventPublisher.getPublishCount()).isEqualTo(1);
	}

	@Test
	void publishNextPendingEvent_keepsOutboxPendingWhenStatusTransitionFailsAfterKafkaPublication() {
		clock.failStatusTransition();

		assertThatThrownBy(outboxEventRetryService::publishNextPendingEvent)
				.isInstanceOf(NullPointerException.class);

		assertThat(findOutboxStatus()).isEqualTo("PENDING");
		assertThat(findPublishedAt()).isNull();
		assertThat(orderCompletedEventPublisher.getPublishCount()).isEqualTo(1);
	}

	@Test
	void publishNextPendingEvent_allowsOnlyOnePublisherToPublishContendedEvent() throws Exception {
		orderCompletedEventPublisher.blockNextPublication();
		ExecutorService executorService = Executors.newFixedThreadPool(2);

		try {
			Future<Boolean> first = executorService.submit(outboxEventRetryService::publishNextPendingEvent);

			assertThat(orderCompletedEventPublisher.awaitPublicationStart()).isTrue();
			Future<Boolean> second = executorService.submit(outboxEventRetryService::publishNextPendingEvent);

			orderCompletedEventPublisher.allowPublication();

			assertThat(first.get(10, TimeUnit.SECONDS)).isTrue();
			assertThat(second.get(10, TimeUnit.SECONDS)).isFalse();
		} finally {
			executorService.shutdownNow();
		}

		assertThat(findOutboxStatus()).isEqualTo("PUBLISHED");
		assertThat(orderCompletedEventPublisher.getPublishCount()).isEqualTo(1);
	}

	private void createOrderCompletedTopic() throws Exception {
		try (AdminClient adminClient = AdminClient.create(Map.of(
				AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
				KAFKA.getBootstrapServers()
		))) {
			if (adminClient.listTopics().names().get(10, TimeUnit.SECONDS).contains(ORDER_COMPLETED_TOPIC)) {
				return;
			}

			adminClient.createTopics(List.of(new NewTopic(ORDER_COMPLETED_TOPIC, 1, (short) 1)))
					.all()
					.get(10, TimeUnit.SECONDS);
		}
	}

	private Consumer<String, OrderCompletedEvent> createConsumer() {
		JacksonJsonDeserializer<OrderCompletedEvent> valueDeserializer = new JacksonJsonDeserializer<>(
				OrderCompletedEvent.class
		);
		valueDeserializer.addTrustedPackages("com.coffeepointordersystem.domain.order.event");

		return new KafkaConsumer<>(
				Map.of(
						ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
						KAFKA.getBootstrapServers(),
						ConsumerConfig.GROUP_ID_CONFIG,
						"outbox-retry-integration-" + UUID.randomUUID(),
						ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
						"latest"
				),
				new StringDeserializer(),
				valueDeserializer
		);
	}

	private ConsumerRecord<String, OrderCompletedEvent> awaitOrderCompletedEvent(
			Consumer<String, OrderCompletedEvent> consumer
	) {
		for (int attempt = 0; attempt < 10; attempt++) {
			for (ConsumerRecord<String, OrderCompletedEvent> record : consumer.poll(Duration.ofSeconds(1))) {
				if (record.value().orderId() == 101L) {
					return record;
				}
			}
		}

		throw new AssertionError("Kafka에서 재시도한 주문 완료 이벤트를 받지 못했습니다.");
	}

	private void awaitPartitionAssignment(Consumer<String, OrderCompletedEvent> consumer) {
		for (int attempt = 0; attempt < 10; attempt++) {
			consumer.poll(Duration.ofSeconds(1));
			if (!consumer.assignment().isEmpty()) {
				return;
			}
		}

		assertThat(consumer.assignment()).isNotEmpty();
	}

	private void insertPendingOutboxEvent() {
		jdbcTemplate.update(
				"INSERT INTO point_accounts (user_id, balance) VALUES (?, ?)",
				TEST_USER_ID,
				10_000L
		);
		jdbcTemplate.update(
				"INSERT INTO orders (user_id, menu_id, paid_amount, ordered_at) VALUES (?, ?, ?, ?)",
				TEST_USER_ID,
				1L,
				4_500L,
				Timestamp.from(ORDERED_AT)
		);
		Long orderId = jdbcTemplate.queryForObject(
				"SELECT id FROM orders WHERE user_id = ?",
				Long.class,
				TEST_USER_ID
		);
		jdbcTemplate.update(
				"""
						INSERT INTO outbox_events (order_id, payload, status, created_at, published_at)
						VALUES (?, ?, 'PENDING', ?, NULL)
						""",
				orderId,
				"""
						{"orderId":101,"userId":"outbox-retry-integration-user","menuId":1,
						"paidAmount":4500,"occurredAt":"2026-07-17T01:00:00.123456Z"}
						""",
				Timestamp.from(ORDERED_AT)
		);
	}

	private String findOutboxStatus() {
		return jdbcTemplate.queryForObject(
				"SELECT outbox_events.status FROM outbox_events "
						+ "INNER JOIN orders ON outbox_events.order_id = orders.id "
						+ "WHERE orders.user_id = ?",
				String.class,
				TEST_USER_ID
		);
	}

	private Timestamp findPublishedAt() {
		return jdbcTemplate.queryForObject(
				"SELECT outbox_events.published_at FROM outbox_events "
						+ "INNER JOIN orders ON outbox_events.order_id = orders.id "
						+ "WHERE orders.user_id = ?",
				Timestamp.class,
				TEST_USER_ID
		);
	}

	private OrderCompletedEvent orderCompletedEvent() {
		return new OrderCompletedEvent(101L, TEST_USER_ID, 1L, 4_500L, ORDERED_AT);
	}

	@TestConfiguration
	static class RetryTestConfig {

		@Bean
		@Primary
		ControllableOrderCompletedEventPublisher orderCompletedEventPublisher(
				KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate
		) {
			return new ControllableOrderCompletedEventPublisher(kafkaTemplate);
		}

		@Bean
		@Primary
		MutableClock clock() {
			return new MutableClock(PUBLISHED_AT);
		}

	}

	static class ControllableOrderCompletedEventPublisher implements OrderCompletedEventPublisher {

		private final KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate;
		private final AtomicBoolean kafkaPublicationFailure = new AtomicBoolean();
		private final AtomicBoolean blockPublication = new AtomicBoolean();
		private final AtomicInteger publishCount = new AtomicInteger();
		private volatile CountDownLatch publicationStarted = new CountDownLatch(1);
		private volatile CountDownLatch allowPublication = new CountDownLatch(0);

		ControllableOrderCompletedEventPublisher(KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate) {
			this.kafkaTemplate = kafkaTemplate;
		}

		@Override
		public void publish(OrderCompletedEvent orderCompletedEvent, Duration timeout) {
			if (kafkaPublicationFailure.get()) {
				throw new IllegalStateException("Kafka publish failure");
			}

			awaitPublicationPermission();

			try {
				kafkaTemplate.send(
							ORDER_COMPLETED_TOPIC,
							Long.toString(orderCompletedEvent.orderId()),
							orderCompletedEvent
					)
						.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("Kafka 발행 완료 대기 중 인터럽트가 발생했습니다.", exception);
			} catch (Exception exception) {
				throw new IllegalStateException("Kafka publish failure", exception);
			}

			publishCount.incrementAndGet();
		}

		void reset() {
			kafkaPublicationFailure.set(false);
			blockPublication.set(false);
			publishCount.set(0);
			publicationStarted = new CountDownLatch(1);
			allowPublication = new CountDownLatch(0);
		}

		void failKafkaPublication() {
			kafkaPublicationFailure.set(true);
		}

		void recoverKafkaPublication() {
			kafkaPublicationFailure.set(false);
		}

		void blockNextPublication() {
			blockPublication.set(true);
			publicationStarted = new CountDownLatch(1);
			allowPublication = new CountDownLatch(1);
		}

		boolean awaitPublicationStart() throws InterruptedException {
			return publicationStarted.await(10, TimeUnit.SECONDS);
		}

		void allowPublication() {
			allowPublication.countDown();
		}

		int getPublishCount() {
			return publishCount.get();
		}

		private void awaitPublicationPermission() {
			if (!blockPublication.compareAndSet(true, false)) {
				return;
			}

			publicationStarted.countDown();
			try {
				if (!allowPublication.await(10, TimeUnit.SECONDS)) {
					throw new IllegalStateException("경합 테스트 Kafka 발행 대기 시간이 초과되었습니다.");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException("경합 테스트 Kafka 발행 대기 중 인터럽트가 발생했습니다.", exception);
			}
		}

	}

	static class MutableClock extends Clock {

		private final Instant instant;
		private final AtomicBoolean statusTransitionFailure = new AtomicBoolean();

		MutableClock(Instant instant) {
			this.instant = instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			if (statusTransitionFailure.get()) {
				return null;
			}

			return instant;
		}

		void reset() {
			statusTransitionFailure.set(false);
		}

		void failStatusTransition() {
			statusTransitionFailure.set(true);
		}

	}

}
