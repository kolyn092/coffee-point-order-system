package com.coffeepointordersystem.domain.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffeepointordersystem.domain.order.service.CreateOrderApplicationService;
import com.coffeepointordersystem.domain.point.service.PointService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
class OrderConcurrencyTest {

	private static final int CONCURRENT_REQUEST_COUNT = 8;
	private static final long MENU_ID = 1L;
	private static final long MENU_PRICE = 4_500L;
	private static final long CHARGE_AMOUNT = 1_000L;
	private static final String USER_ID = "order-concurrency-user";

	@Container
	static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
			.withDatabaseName("coffee_point_order")
			.withUsername("test")
			.withPassword("test");

	@Autowired
	private CreateOrderApplicationService createOrderApplicationService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PointService pointService;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
		registry.add("spring.kafka.producer.properties.max.block.ms", () -> "100");
	}

	@BeforeEach
	void createPointAccount() {
		insertPointAccount(18_000L);
	}

	@AfterEach
	void cleanUpTestData() {
		jdbcTemplate.update(
				"DELETE outbox_events FROM outbox_events "
						+ "INNER JOIN orders ON outbox_events.order_id = orders.id "
						+ "WHERE orders.user_id = ?",
				USER_ID
		);
		jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", USER_ID);
		jdbcTemplate.update("DELETE FROM point_accounts WHERE user_id = ?", USER_ID);
	}

	@Test
	void createOrder_concurrentOrdersAllowOnlyAffordablePayments() throws Exception {
		jdbcTemplate.update(
				"UPDATE point_accounts SET balance = ? WHERE user_id = ?",
				MENU_PRICE,
				USER_ID
		);

		List<Future<?>> futures = runConcurrently(() -> createOrderApplicationService.create(USER_ID, MENU_ID));
		int successCount = countSuccessfulRequests(futures);

		assertThat(successCount).isEqualTo(1);
		assertThat(findBalance()).isZero();
		assertThat(findOrderCount()).isEqualTo(1L);
		assertThat(findOutboxCount()).isEqualTo(1L);
	}

	@Test
	void createOrder_concurrentOrdersAndChargesPreserveFinalBalance() throws Exception {
		List<Future<?>> futures = runConcurrentlyWithCharges();

		assertThat(countSuccessfulRequests(futures)).isEqualTo(CONCURRENT_REQUEST_COUNT);
		assertThat(findBalance()).isEqualTo(4_000L);
		assertThat(findOrderCount()).isEqualTo(CONCURRENT_REQUEST_COUNT / 2L);
		assertThat(findOutboxCount()).isEqualTo(CONCURRENT_REQUEST_COUNT / 2L);
	}

	private List<Future<?>> runConcurrently(Runnable command) throws Exception {
		ExecutorService executorService = Executors.newFixedThreadPool(CONCURRENT_REQUEST_COUNT);
		CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUEST_COUNT);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();

		try {
			for (int index = 0; index < CONCURRENT_REQUEST_COUNT; index++) {
				futures.add(executorService.submit(() -> {
					ready.countDown();
					awaitStart(start);
					command.run();
				}));
			}

			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			return futures;
		} finally {
			executorService.shutdown();
		}
	}

	private List<Future<?>> runConcurrentlyWithCharges() throws Exception {
		ExecutorService executorService = Executors.newFixedThreadPool(CONCURRENT_REQUEST_COUNT);
		CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUEST_COUNT);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<?>> futures = new ArrayList<>();

		try {
			for (int index = 0; index < CONCURRENT_REQUEST_COUNT / 2; index++) {
				futures.add(executorService.submit(() -> {
					ready.countDown();
					awaitStart(start);
					createOrderApplicationService.create(USER_ID, MENU_ID);
				}));
				futures.add(executorService.submit(() -> {
					ready.countDown();
					awaitStart(start);
					pointService.charge(USER_ID, CHARGE_AMOUNT);
				}));
			}

			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();
			return futures;
		} finally {
			executorService.shutdown();
		}
	}

	private int countSuccessfulRequests(List<Future<?>> futures) throws Exception {
		int successCount = 0;

		for (Future<?> future : futures) {
			try {
				future.get(20, TimeUnit.SECONDS);
				successCount++;
			} catch (ExecutionException exception) {
				assertThat(exception.getCause()).isInstanceOf(RuntimeException.class);
			}
		}

		return successCount;
	}

	private void awaitStart(CountDownLatch start) {
		try {
			if (!start.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("동시 요청 시작 대기 시간이 초과되었습니다.");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("동시 요청 시작 대기 중 인터럽트되었습니다.", exception);
		}
	}

	private void insertPointAccount(long balance) {
		jdbcTemplate.update(
				"INSERT INTO point_accounts (user_id, balance) VALUES (?, ?)",
				USER_ID,
				balance
		);
	}

	private long findBalance() {
		return jdbcTemplate.queryForObject(
				"SELECT balance FROM point_accounts WHERE user_id = ?",
				Long.class,
				USER_ID
		);
	}

	private long findOrderCount() {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM orders WHERE user_id = ?",
				Long.class,
				USER_ID
		);
	}

	private long findOutboxCount() {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM outbox_events "
						+ "INNER JOIN orders ON outbox_events.order_id = orders.id "
						+ "WHERE orders.user_id = ?",
				Long.class,
				USER_ID
		);
	}

}
