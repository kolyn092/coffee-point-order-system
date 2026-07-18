package com.coffeepointordersystem.domain.menu;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffeepointordersystem.domain.menu.exception.PopularMenuUnavailableException;
import com.coffeepointordersystem.domain.menu.repository.PopularMenuQueryRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(PopularMenuRedisFailureIntegrationTest.FixedClockConfig.class)
class PopularMenuRedisFailureIntegrationTest {

	private static final LocalDate TODAY = LocalDate.parse("2026-07-15");
	private static final String FALLBACK_USER_ID = "popular-menu-fallback-user";

	@Container
	static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
			.withDatabaseName("coffee_point_order")
			.withUsername("test")
			.withPassword("test");

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoSpyBean
	private PopularMenuQueryRepository popularMenuQueryRepository;

	@DynamicPropertySource
	static void registerProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
		registry.add("spring.data.redis.host", () -> "127.0.0.1");
		registry.add("spring.data.redis.port", () -> 1);
		registry.add("spring.data.redis.connect-timeout", () -> "100ms");
		registry.add("spring.data.redis.timeout", () -> "100ms");
		registry.add("spring.kafka.bootstrap-servers", () -> "127.0.0.1:1");
	}

	@Test
	void getPopularMenus_returnsMySqlFallbackWhenRedisIsUnavailable() throws Exception {
		insertFallbackOrder();

		mockMvc.perform(get("/api/v1/menus/popular"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data[0].menuId").value(1L))
				.andExpect(jsonPath("$.data[0].orderCount").value(1L));
	}

	@AfterEach
	void cleanUp() {
		jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", FALLBACK_USER_ID);
		jdbcTemplate.update("DELETE FROM point_accounts WHERE user_id = ?", FALLBACK_USER_ID);
	}

	@Test
	void getPopularMenus_returnsServiceUnavailableWhenRedisAndMySqlAreUnavailable() throws Exception {
		org.mockito.Mockito.doThrow(new PopularMenuUnavailableException())
				.when(popularMenuQueryRepository)
				.findOrderCounts(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());

		mockMvc.perform(get("/api/v1/menus/popular"))
				.andExpect(status().isServiceUnavailable())
				.andExpect(jsonPath("$.code").value("POPULAR_MENU_UNAVAILABLE"))
				.andExpect(jsonPath("$.message").value("인기 메뉴를 조회할 수 없습니다."))
				.andExpect(jsonPath("$.data").doesNotExist());
	}

	private void insertFallbackOrder() {
		jdbcTemplate.update(
				"INSERT INTO point_accounts (user_id, balance) VALUES (?, ?)",
				FALLBACK_USER_ID,
				10_000L
		);
		jdbcTemplate.update(
				"INSERT INTO orders (user_id, menu_id, paid_amount, ordered_at) VALUES (?, ?, ?, ?)",
				FALLBACK_USER_ID,
				1L,
				4500L,
				Instant.parse("2026-07-15T03:04:05Z")
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
