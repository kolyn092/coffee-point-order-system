package com.coffeepointordersystem.domain.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class OrderKafkaFailureIntegrationTest {

	private static final String TEST_USER_ID = "order-kafka-failure-user";

	@Container
	static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.36")
			.withDatabaseName("coffee_point_order")
			.withUsername("test")
			.withPassword("test");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private MockMvc mockMvc;

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
		jdbcTemplate.update(
				"INSERT INTO point_accounts (user_id, balance) VALUES (?, ?)",
				TEST_USER_ID,
				10_000L
		);
	}

	@AfterEach
	void cleanUpTestData() {
		jdbcTemplate.update("DELETE FROM orders WHERE user_id = ?", TEST_USER_ID);
		jdbcTemplate.update("DELETE FROM point_accounts WHERE user_id = ?", TEST_USER_ID);
	}

	@Test
	void createOrder_keepsSuccessResponseWhenKafkaPublicationFails() throws Exception {
		mockMvc.perform(post("/api/v1/orders")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "userId": "order-kafka-failure-user",
							  "menuId": 1
							}
							"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.remainingPointBalance").value(5_500L));

		assertThat(jdbcTemplate.queryForObject(
				"SELECT balance FROM point_accounts WHERE user_id = ?",
				Long.class,
				TEST_USER_ID
		)).isEqualTo(5_500L);
		assertThat(jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM orders WHERE user_id = ?",
				Long.class,
				TEST_USER_ID
		)).isEqualTo(1L);
	}

}
