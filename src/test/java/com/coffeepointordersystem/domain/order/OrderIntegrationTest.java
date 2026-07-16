package com.coffeepointordersystem.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffeepointordersystem.domain.outbox.event.OrderCompletedOutboxEvent;
import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import com.coffeepointordersystem.domain.order.service.CreateOrderApplicationService;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(OrderIntegrationTest.RollbackTestConfig.class)
class OrderIntegrationTest {

	private static final long TEST_MENU_ID = 100L;
	private static final long MENU_PRICE = 4_500L;
	private static final String ORDER_COMPLETED_TOPIC = "order.completed";
	private static final String SUCCESS_USER_ID = "order-success-user";
	private static final String INSUFFICIENT_BALANCE_USER_ID = "order-insufficient-user";
	private static final String ROLLBACK_USER_ID = "order-rollback-user";
	private static final AtomicBoolean rollbackFailure = new AtomicBoolean();

	@Container
	static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
			.withDatabaseName("coffee_point_order")
			.withUsername("test")
			.withPassword("test");

	@Container
	static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.0"));

	@Autowired
	private CreateOrderApplicationService createOrderApplicationService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private JsonMapper jsonMapper;

	@Autowired
	private MockMvc mockMvc;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
	}

	@BeforeEach
	void createMenu() {
		jdbcTemplate.update(
				"INSERT INTO menus (id, name, price) VALUES (?, ?, ?)",
				TEST_MENU_ID,
				"주문 테스트 메뉴",
				MENU_PRICE
		);
	}

	@AfterEach
	void cleanUpTestData() {
		rollbackFailure.set(false);
		jdbcTemplate.update(
				"DELETE outbox_events FROM outbox_events "
						+ "INNER JOIN orders ON outbox_events.order_id = orders.id "
						+ "WHERE orders.user_id IN (?, ?, ?)",
				SUCCESS_USER_ID,
				INSUFFICIENT_BALANCE_USER_ID,
				ROLLBACK_USER_ID
		);
		jdbcTemplate.update(
				"DELETE FROM orders WHERE user_id IN (?, ?, ?)",
				SUCCESS_USER_ID,
				INSUFFICIENT_BALANCE_USER_ID,
				ROLLBACK_USER_ID
		);
		jdbcTemplate.update(
				"DELETE FROM point_accounts WHERE user_id IN (?, ?, ?)",
				SUCCESS_USER_ID,
				INSUFFICIENT_BALANCE_USER_ID,
				ROLLBACK_USER_ID
		);
		jdbcTemplate.update("DELETE FROM menus WHERE id = ?", TEST_MENU_ID);
	}

	@Test
	void createOrder_returnsCreatedResponseAndPersistsPayment() throws Exception {
		insertPointAccount(SUCCESS_USER_ID, 10_000L);

		mockMvc.perform(createOrderRequest(SUCCESS_USER_ID, TEST_MENU_ID))
				.andExpect(status().isCreated())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data.orderId").isNumber())
				.andExpect(jsonPath("$.data.userId").value(SUCCESS_USER_ID))
				.andExpect(jsonPath("$.data.menuId").value(TEST_MENU_ID))
				.andExpect(jsonPath("$.data.paidAmount").value(MENU_PRICE))
				.andExpect(jsonPath("$.data.remainingPointBalance").value(5_500L))
				.andExpect(jsonPath("$.data.orderedAt").isNotEmpty());

		assertThat(findBalance(SUCCESS_USER_ID)).isEqualTo(5_500L);
		assertThat(findOrderCount(SUCCESS_USER_ID)).isEqualTo(1L);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT paid_amount FROM orders WHERE user_id = ?",
				Long.class,
				SUCCESS_USER_ID
		)).isEqualTo(MENU_PRICE);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT ordered_at FROM orders WHERE user_id = ?",
				java.sql.Timestamp.class,
				SUCCESS_USER_ID
		)).isNotNull();
	}

	@Test
	void createOrder_returnsInvalidRequestBeforeDomainLookup() throws Exception {
		insertPointAccount(SUCCESS_USER_ID, 10_000L);

		mockMvc.perform(createOrderRequest(" invalid-user", 0L))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		assertThat(findBalance(SUCCESS_USER_ID)).isEqualTo(10_000L);
		assertThat(findOrderCount(SUCCESS_USER_ID)).isZero();
	}

	@Test
	void createOrder_returnsNotFoundWhenMenuIsMissingWithoutChangingBalance() throws Exception {
		insertPointAccount(SUCCESS_USER_ID, 10_000L);

		mockMvc.perform(createOrderRequest(SUCCESS_USER_ID, 999L))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("MENU_NOT_FOUND"))
				.andExpect(jsonPath("$.message").value("메뉴를 찾을 수 없습니다."))
				.andExpect(jsonPath("$.data").doesNotExist());

		assertThat(findBalance(SUCCESS_USER_ID)).isEqualTo(10_000L);
		assertThat(findOrderCount(SUCCESS_USER_ID)).isZero();
	}

	@Test
	void createOrder_returnsNotFoundWhenPointAccountIsMissing() throws Exception {
		mockMvc.perform(createOrderRequest("order-missing-account-user", TEST_MENU_ID))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.code").value("POINT_ACCOUNT_NOT_FOUND"));

		assertThat(findOrderCount("order-missing-account-user")).isZero();
	}

	@Test
	void createOrder_returnsConflictWhenPointBalanceIsInsufficient() throws Exception {
		insertPointAccount(INSUFFICIENT_BALANCE_USER_ID, MENU_PRICE - 1L);

		mockMvc.perform(createOrderRequest(INSUFFICIENT_BALANCE_USER_ID, TEST_MENU_ID))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("INSUFFICIENT_POINT_BALANCE"));

		assertThat(findBalance(INSUFFICIENT_BALANCE_USER_ID)).isEqualTo(MENU_PRICE - 1L);
		assertThat(findOrderCount(INSUFFICIENT_BALANCE_USER_ID)).isZero();
	}

	@Test
	void createOrder_rollsBackPointAndOrderWhenCommitFails() {
		insertPointAccount(ROLLBACK_USER_ID, 10_000L);
		rollbackFailure.set(true);

		assertThatThrownBy(() -> createOrderApplicationService.create(ROLLBACK_USER_ID, TEST_MENU_ID))
				.isInstanceOf(IllegalStateException.class)
				.hasMessage("rollback test failure");

		assertThat(findBalance(ROLLBACK_USER_ID)).isEqualTo(10_000L);
		assertThat(findOrderCount(ROLLBACK_USER_ID)).isZero();
		assertThat(findOutboxCount(ROLLBACK_USER_ID)).isZero();
	}

	@Test
	void createOrder_publishesCommittedOrderCompletedEvent() throws Exception {
		insertPointAccount(SUCCESS_USER_ID, 10_000L);
		createOrderCompletedTopic();

		try (Consumer<String, OrderCompletedEvent> consumer = createConsumer()) {
			consumer.subscribe(List.of(ORDER_COMPLETED_TOPIC));
			awaitPartitionAssignment(consumer);

			mockMvc.perform(createOrderRequest(SUCCESS_USER_ID, TEST_MENU_ID))
					.andExpect(status().isCreated());

			ConsumerRecords<String, OrderCompletedEvent> records = consumer.poll(Duration.ofSeconds(10));
			assertThat(records.records(ORDER_COMPLETED_TOPIC)).hasSize(1);

			OrderCompletedEvent event = records.records(ORDER_COMPLETED_TOPIC).iterator().next().value();
			Long orderId = jdbcTemplate.queryForObject(
					"SELECT id FROM orders WHERE user_id = ?",
					Long.class,
					SUCCESS_USER_ID
			);

			assertThat(event.orderId()).isEqualTo(orderId);
			assertThat(event.userId()).isEqualTo(SUCCESS_USER_ID);
			assertThat(event.menuId()).isEqualTo(TEST_MENU_ID);
			assertThat(event.paidAmount()).isEqualTo(MENU_PRICE);
			assertThat(event.occurredAt()).isEqualTo(jdbcTemplate.queryForObject(
					"SELECT ordered_at FROM orders WHERE user_id = ?",
					java.sql.Timestamp.class,
					SUCCESS_USER_ID
			).toInstant());
			assertThat(findOutboxCount(SUCCESS_USER_ID)).isEqualTo(1L);
			awaitOutboxPublished(consumer, SUCCESS_USER_ID);
			assertThat(findOutboxStatus(SUCCESS_USER_ID)).isEqualTo("PUBLISHED");
			assertThat(jdbcTemplate.queryForObject(
					"SELECT outbox_events.published_at FROM outbox_events "
							+ "INNER JOIN orders ON outbox_events.order_id = orders.id "
							+ "WHERE orders.user_id = ?",
					java.sql.Timestamp.class,
					SUCCESS_USER_ID
			)).isNotNull();
			String payload = jdbcTemplate.queryForObject(
					"SELECT outbox_events.payload FROM outbox_events "
							+ "INNER JOIN orders ON outbox_events.order_id = orders.id "
							+ "WHERE orders.user_id = ?",
					String.class,
					SUCCESS_USER_ID
			);
			JsonNode payloadJson = jsonMapper.readTree(payload);

			assertThat(payloadJson.path("orderId").asLong()).isEqualTo(orderId);
			assertThat(payloadJson.path("userId").asString()).isEqualTo(SUCCESS_USER_ID);
			assertThat(payloadJson.path("menuId").asLong()).isEqualTo(TEST_MENU_ID);
			assertThat(payloadJson.path("paidAmount").asLong()).isEqualTo(MENU_PRICE);
			assertThat(payloadJson.path("occurredAt").asString()).isEqualTo(event.occurredAt().toString());
		}
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder createOrderRequest(
			String userId,
			long menuId
	) {
		return post("/api/v1/orders")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{
						  "userId": "%s",
						  "menuId": %d
						}
						""".formatted(userId, menuId));
	}

	private void insertPointAccount(String userId, long balance) {
		jdbcTemplate.update(
				"INSERT INTO point_accounts (user_id, balance) VALUES (?, ?)",
				userId,
				balance
		);
	}

	private long findBalance(String userId) {
		return jdbcTemplate.queryForObject(
				"SELECT balance FROM point_accounts WHERE user_id = ?",
				Long.class,
				userId
		);
	}

	private long findOrderCount(String userId) {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM orders WHERE user_id = ?",
				Long.class,
				userId
		);
	}

	private long findOutboxCount(String userId) {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM outbox_events "
						+ "INNER JOIN orders ON outbox_events.order_id = orders.id "
						+ "WHERE orders.user_id = ?",
				Long.class,
				userId
		);
	}

	private void awaitOutboxPublished(Consumer<String, OrderCompletedEvent> consumer, String userId) {
		for (int attempt = 0; attempt < 10; attempt++) {
			if (findOutboxStatus(userId).equals("PUBLISHED")) {
				return;
			}

			consumer.poll(Duration.ofSeconds(1));
		}

		assertThat(findOutboxStatus(userId)).isEqualTo("PUBLISHED");
	}

	private String findOutboxStatus(String userId) {
		return jdbcTemplate.queryForObject(
				"SELECT outbox_events.status FROM outbox_events "
						+ "INNER JOIN orders ON outbox_events.order_id = orders.id "
						+ "WHERE orders.user_id = ?",
				String.class,
				userId
		);
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
						"order-integration-" + UUID.randomUUID(),
						ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
						"latest"
				),
				new StringDeserializer(),
				valueDeserializer
		);
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

	@TestConfiguration
	static class RollbackTestConfig {

		@Bean
		RollbackTestListener rollbackTestListener() {
			return new RollbackTestListener();
		}

	}

	static class RollbackTestListener {

		@TransactionalEventListener(phase = TransactionPhase.BEFORE_COMMIT)
		public void failCommit(OrderCompletedOutboxEvent event) {
			if (rollbackFailure.get()) {
				throw new IllegalStateException("rollback test failure");
			}
		}

	}

}
