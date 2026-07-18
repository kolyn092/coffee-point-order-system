package com.coffeepointordersystem.infra.database;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
class MySqlPopularMenuRebuildLockIntegrationTest {

	@Container
	static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
			.withDatabaseName("coffee_point_order")
			.withUsername("test")
			.withPassword("test");

	@Test
	void executeIfAcquired_allowsOnlyOneConcurrentRebuildOwner() throws Exception {
		MySqlPopularMenuRebuildLock rebuildLock = new MySqlPopularMenuRebuildLock(createDataSource());
		CountDownLatch acquired = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		ExecutorService executorService = Executors.newSingleThreadExecutor();

		try {
			Future<Boolean> firstAttempt = executorService.submit(() -> rebuildLock.executeIfAcquired(() -> {
				acquired.countDown();
				await(release);
			}));
			assertThat(acquired.await(10L, TimeUnit.SECONDS)).isTrue();

			boolean secondAttempt = rebuildLock.executeIfAcquired(() -> {
			});

			assertThat(secondAttempt).isFalse();
			release.countDown();
			assertThat(firstAttempt.get(10L, TimeUnit.SECONDS)).isTrue();
		} finally {
			executorService.shutdownNow();
		}
	}

	private DriverManagerDataSource createDataSource() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setUrl(MYSQL.getJdbcUrl());
		dataSource.setUsername(MYSQL.getUsername());
		dataSource.setPassword(MYSQL.getPassword());
		return dataSource;
	}

	private void await(CountDownLatch latch) {
		try {
			if (!latch.await(10L, TimeUnit.SECONDS)) {
				throw new IllegalStateException("명명 잠금 해제 대기 시간이 초과되었습니다.");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("명명 잠금 해제를 기다리는 중 인터럽트되었습니다.", exception);
		}
	}

}
