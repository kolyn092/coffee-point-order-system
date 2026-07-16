package com.coffeepointordersystem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class InfrastructureIntegrationTest {
	private static final long TEST_MENU_ID = 100L;
	private static final String TEST_USER_ID = "m0-test-user";
	private static final Instant TEST_ORDERED_AT = Instant.parse("2026-07-15T03:04:05.123456Z");
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
	private Environment environment;

	@Autowired
	private StringRedisTemplate stringRedisTemplate;

	@DynamicPropertySource
	static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", REDIS::getFirstMappedPort);
		registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
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
		jdbcTemplate.update("DELETE FROM menus WHERE id = ?", TEST_MENU_ID);
	}

	@Test
	void applicationContext_startsWithMySqlConfiguration() {
		assertThat(environment.getProperty("spring.application.name"))
				.isEqualTo("coffee-point-order-system");
		assertThat(environment.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone"))
				.isEqualTo("UTC");
	}

	@Test
	void applicationContext_connectsRedisAndKafka() throws Exception {
		String redisResponse = stringRedisTemplate.execute(
				(RedisCallback<String>) connection -> connection.ping()
		);

		assertThat(redisResponse).isEqualTo("PONG");

		try (AdminClient adminClient = AdminClient.create(Map.of(
				AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG,
				environment.getRequiredProperty("spring.kafka.bootstrap-servers")
		))) {
			assertThat(adminClient.describeCluster().nodes().get(10, TimeUnit.SECONDS)).isNotEmpty();
		}
	}

	@Test
	void flywayMigration_createsExpectedTablesAndInitialMenus() {
		Long tableCount = jdbcTemplate.queryForObject(
				"""
						SELECT COUNT(*)
						FROM information_schema.tables
						WHERE table_schema = DATABASE()
						  AND table_name IN ('menus', 'point_accounts', 'orders', 'outbox_events')
						""",
				Long.class
		);

		assertThat(tableCount).isEqualTo(4L);
		assertThat(jdbcTemplate.queryForList(
				"SELECT name FROM menus ORDER BY id",
				String.class
		)).containsExactly("아메리카노", "카페라떼");
		assertThat(jdbcTemplate.queryForList(
				"SELECT price FROM menus ORDER BY id",
				Long.class
		)).containsExactly(4500L, 5000L);
	}

	@Test
	void flywayMigration_createsRequiredOutboxConstraintsAndIndex() {
		insertValidOrder();
		Long orderId = jdbcTemplate.queryForObject(
				"SELECT id FROM orders WHERE user_id = ?",
				Long.class,
				TEST_USER_ID
		);
		List<String> constraintNames = jdbcTemplate.queryForList(
				"SELECT constraint_name FROM information_schema.table_constraints "
						+ "WHERE table_schema = DATABASE() AND table_name = 'outbox_events'",
				String.class
		);
		List<String> indexNames = jdbcTemplate.queryForList(
				"SELECT DISTINCT index_name FROM information_schema.statistics "
						+ "WHERE table_schema = DATABASE() AND table_name = 'outbox_events'",
				String.class
		);

		assertThat(constraintNames).contains(
				"PRIMARY",
				"uk_outbox_events_order_id",
				"fk_outbox_events_order",
				"chk_outbox_events_status",
				"chk_outbox_events_status_published_at"
		);
		assertThat(indexNames).contains(
				"PRIMARY",
				"uk_outbox_events_order_id",
				"idx_outbox_events_status_id"
		);
		assertThatThrownBy(() -> insertOutbox(orderId, "INVALID"))
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("Check constraint");
		assertThatThrownBy(() -> insertOutbox(orderId, "PUBLISHED"))
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("Check constraint");
		assertThatThrownBy(() -> insertOutbox(orderId, "PENDING", Timestamp.from(TEST_ORDERED_AT)))
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("Check constraint");

		insertOutbox(orderId, "PENDING");

		assertThatThrownBy(() -> insertOutbox(orderId, "PENDING"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbcTemplate.update(
				"DELETE FROM orders WHERE id = ?",
				orderId
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void schema_preservesOrderTimestampPrecisionAndUtcSession() {
		insertValidReferences();
		insertOrder(TEST_USER_ID, TEST_MENU_ID, 4500L);

		String sessionTimeZone = jdbcTemplate.queryForObject(
				"SELECT @@session.time_zone",
				String.class
		);
		String storedTimestamp = jdbcTemplate.queryForObject(
				"SELECT DATE_FORMAT(ordered_at, '%Y-%m-%d %H:%i:%s.%f') "
						+ "FROM orders WHERE user_id = ?",
				String.class,
				TEST_USER_ID
		);

		assertThat(sessionTimeZone).isIn("UTC", "+00:00");
		assertThat(environment.getProperty("spring.datasource.hikari.connection-init-sql"))
				.isEqualTo("SET time_zone = '+00:00'");
		assertThat(environment.getProperty(
				"spring.datasource.hikari.data-source-properties.connectionTimeZone"
		)).isEqualTo("UTC");
		assertThat(storedTimestamp).isEqualTo("2026-07-15 03:04:05.123456");
		assertThat(jdbcTemplate.queryForObject(
				"SELECT datetime_precision FROM information_schema.columns "
						+ "WHERE table_schema = DATABASE() AND table_name = 'orders' "
						+ "AND column_name = 'ordered_at'",
				Integer.class
		)).isEqualTo(6);
	}

	@Test
	void schema_rejectsBlankMenuName() {
		assertThatThrownBy(() -> insertMenu(TEST_MENU_ID, "   ", 4500L))
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("Check constraint");
	}

	@Test
	void schema_rejectsNonPositiveMenuPrice() {
		assertThatThrownBy(() -> insertMenu(TEST_MENU_ID, "테스트 메뉴", 0L))
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("Check constraint");
	}

	@Test
	void schema_rejectsNegativePointBalance() {
		assertThatThrownBy(() -> insertPointAccount(TEST_USER_ID, -1L))
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("Check constraint");
	}

	@Test
	void schema_rejectsNonPositiveOrderAmount() {
		insertValidReferences();

		assertThatThrownBy(() -> insertOrder(TEST_USER_ID, TEST_MENU_ID, 0L))
				.isInstanceOf(UncategorizedSQLException.class)
				.hasMessageContaining("Check constraint");
	}

	@Test
	void schema_rejectsOrderWithMissingMenu() {
		insertPointAccount(TEST_USER_ID, 4500L);

		assertThatThrownBy(() -> insertOrder(TEST_USER_ID, 999L, 4500L))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void schema_rejectsOrderWithMissingPointAccount() {
		insertMenu(TEST_MENU_ID, "테스트 메뉴", 4500L);

		assertThatThrownBy(() -> insertOrder("m0-missing-account", TEST_MENU_ID, 4500L))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void schema_createsRequiredOrderIndexes() {
		List<String> indexNames = jdbcTemplate.queryForList(
				"SELECT DISTINCT index_name FROM information_schema.statistics "
						+ "WHERE table_schema = DATABASE() AND table_name = 'orders'",
				String.class
		);

		assertThat(indexNames).contains(
				"PRIMARY",
				"idx_orders_user_id",
				"idx_orders_menu_id",
				"idx_orders_ordered_at_menu_id"
		);
	}

	@Test
	void schema_restrictsReferencedMenuDeletion() {
		insertValidOrder();

		assertThatThrownBy(() -> jdbcTemplate.update(
				"DELETE FROM menus WHERE id = ?",
				TEST_MENU_ID
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void schema_restrictsReferencedPointAccountDeletion() {
		insertValidOrder();

		assertThatThrownBy(() -> jdbcTemplate.update(
				"DELETE FROM point_accounts WHERE user_id = ?",
				TEST_USER_ID
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void schema_restrictsReferencedMenuUpdate() {
		insertValidOrder();

		assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE menus SET id = ? WHERE id = ?",
				TEST_MENU_ID + 1L,
				TEST_MENU_ID
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void schema_restrictsReferencedPointAccountUpdate() {
		insertValidOrder();

		assertThatThrownBy(() -> jdbcTemplate.update(
				"UPDATE point_accounts SET user_id = ? WHERE user_id = ?",
				"m0-renamed-user",
				TEST_USER_ID
		)).isInstanceOf(DataIntegrityViolationException.class);
	}

	private void insertValidReferences() {
		insertMenu(TEST_MENU_ID, "테스트 메뉴", 4500L);
		insertPointAccount(TEST_USER_ID, 4500L);
	}

	private void insertValidOrder() {
		insertValidReferences();
		insertOrder(TEST_USER_ID, TEST_MENU_ID, 4500L);
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

	private void insertOrder(String userId, long menuId, long paidAmount) {
		jdbcTemplate.update(
				"INSERT INTO orders (user_id, menu_id, paid_amount, ordered_at) VALUES (?, ?, ?, ?)",
				userId,
				menuId,
				paidAmount,
				Timestamp.from(TEST_ORDERED_AT)
		);
	}

	private void insertOutbox(long orderId, String status) {
		insertOutbox(orderId, status, null);
	}

	private void insertOutbox(long orderId, String status, Timestamp publishedAt) {
		jdbcTemplate.update(
				"""
						INSERT INTO outbox_events (order_id, payload, status, created_at, published_at)
						VALUES (?, ?, ?, ?, ?)
						""",
				orderId,
				"{\"orderId\":%d}".formatted(orderId),
				status,
				Timestamp.from(TEST_ORDERED_AT),
				publishedAt
		);
	}

}
