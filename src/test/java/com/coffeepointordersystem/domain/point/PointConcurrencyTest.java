package com.coffeepointordersystem.domain.point;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffeepointordersystem.domain.point.dto.PointChargeResponse;
import com.coffeepointordersystem.domain.point.service.PointService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
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
class PointConcurrencyTest {

	private static final int CONCURRENT_REQUEST_COUNT = 8;
	private static final long CHARGE_AMOUNT = 100L;
	private static final String PRECREATED_ACCOUNT_USER_ID = "point-concurrency-precreated-user";
	private static final String EXISTING_BALANCE_ACCOUNT_USER_ID = "point-concurrency-existing-user";

	@Container
	static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
			.withDatabaseName("coffee_point_order")
			.withUsername("test")
			.withPassword("test");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PointService pointService;

	@DynamicPropertySource
	static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
	}

	@BeforeEach
	void createPointAccounts() {
		insertPointAccount(PRECREATED_ACCOUNT_USER_ID, 0L);
		insertPointAccount(EXISTING_BALANCE_ACCOUNT_USER_ID, CHARGE_AMOUNT);
	}

	@AfterEach
	void cleanUpPointAccounts() {
		jdbcTemplate.update("DELETE FROM point_accounts WHERE user_id = ?", PRECREATED_ACCOUNT_USER_ID);
		jdbcTemplate.update("DELETE FROM point_accounts WHERE user_id = ?", EXISTING_BALANCE_ACCOUNT_USER_ID);
	}

	@Test
	void charge_concurrentChargesForPrecreatedAccountPreserveEveryAmount() throws Exception {
		List<PointChargeResponse> responses = chargeConcurrently(PRECREATED_ACCOUNT_USER_ID);

		assertThat(responses).hasSize(CONCURRENT_REQUEST_COUNT);
		assertThat(findBalance(PRECREATED_ACCOUNT_USER_ID))
				.isEqualTo(CONCURRENT_REQUEST_COUNT * CHARGE_AMOUNT);
	}

	@Test
	void charge_concurrentChargesForExistingAccountPreserveEveryAmount() throws Exception {
		List<PointChargeResponse> responses = chargeConcurrently(EXISTING_BALANCE_ACCOUNT_USER_ID);

		assertThat(responses).hasSize(CONCURRENT_REQUEST_COUNT);
		assertThat(findBalance(EXISTING_BALANCE_ACCOUNT_USER_ID))
				.isEqualTo((CONCURRENT_REQUEST_COUNT + 1L) * CHARGE_AMOUNT);
	}

	private List<PointChargeResponse> chargeConcurrently(String userId) throws Exception {
		ExecutorService executorService = Executors.newFixedThreadPool(CONCURRENT_REQUEST_COUNT);
		CountDownLatch ready = new CountDownLatch(CONCURRENT_REQUEST_COUNT);
		CountDownLatch start = new CountDownLatch(1);
		List<Future<PointChargeResponse>> futures = new ArrayList<>();

		try {
			for (int index = 0; index < CONCURRENT_REQUEST_COUNT; index++) {
				futures.add(executorService.submit(() -> {
					ready.countDown();
					assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
					return pointService.charge(userId, CHARGE_AMOUNT);
				}));
			}

			assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
			start.countDown();

			List<PointChargeResponse> responses = new ArrayList<>();
			for (Future<PointChargeResponse> future : futures) {
				responses.add(future.get(20, TimeUnit.SECONDS));
			}

			return responses;
		} finally {
			executorService.shutdownNow();
		}
	}

	private long findBalance(String userId) {
		return jdbcTemplate.queryForObject(
				"SELECT balance FROM point_accounts WHERE user_id = ?",
				Long.class,
				userId
		);
	}

	private void insertPointAccount(String userId, long balance) {
		jdbcTemplate.update(
				"INSERT INTO point_accounts (user_id, balance) VALUES (?, ?)",
				userId,
				balance
		);
	}

}
