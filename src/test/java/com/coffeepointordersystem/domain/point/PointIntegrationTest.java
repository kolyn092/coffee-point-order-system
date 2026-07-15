package com.coffeepointordersystem.domain.point;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
class PointIntegrationTest {

	private static final String TEST_USER_ID = "point-integration-user";

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
	static void registerDatabaseProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
		registry.add("spring.datasource.username", MYSQL::getUsername);
		registry.add("spring.datasource.password", MYSQL::getPassword);
	}

	@BeforeEach
	void createPointAccount() {
		jdbcTemplate.update(
				"INSERT INTO point_accounts (user_id, balance) VALUES (?, ?)",
				TEST_USER_ID,
				0L
		);
	}

	@AfterEach
	void cleanUpPointAccounts() {
		jdbcTemplate.update("DELETE FROM point_accounts WHERE user_id = ?", TEST_USER_ID);
	}

	@Test
	void chargePoint_updatesPrecreatedAccountAndReturnsChargeResponse() throws Exception {
		mockMvc.perform(post("/api/v1/points/charges")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "userId": "point-integration-user",
							  "amount": 10000
							}
							"""))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.userId").value(TEST_USER_ID))
				.andExpect(jsonPath("$.chargedAmount").value(10000))
				.andExpect(jsonPath("$.balance").value(10000));

		assertThatPointAccount(TEST_USER_ID, 10000L);
	}

	@Test
	void chargePoint_accumulatesBalanceForExistingAccount() throws Exception {
		chargePoint(10000L);

		mockMvc.perform(post("/api/v1/points/charges")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "userId": "point-integration-user",
							  "amount": 5000
							}
							"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.chargedAmount").value(5000))
				.andExpect(jsonPath("$.balance").value(15000));

		assertThatPointAccount(TEST_USER_ID, 15000L);
	}

	@Test
	void chargePoint_returnsInvalidRequestForBlankUserId() throws Exception {
		mockMvc.perform(post("/api/v1/points/charges")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "userId": "   ",
							  "amount": 10000
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void chargePoint_returnsInvalidRequestForNonPositiveAmount() throws Exception {
		mockMvc.perform(post("/api/v1/points/charges")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "userId": "point-integration-user",
							  "amount": 0
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
	}

	@Test
	void chargePoint_returnsConflictAndPreservesBalanceWhenAdditionOverflows() throws Exception {
		jdbcTemplate.update(
				"UPDATE point_accounts SET balance = ? WHERE user_id = ?",
				Long.MAX_VALUE,
				TEST_USER_ID
		);

		mockMvc.perform(post("/api/v1/points/charges")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "userId": "point-integration-user",
							  "amount": 1
							}
							"""))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("POINT_BALANCE_LIMIT_EXCEEDED"));

		assertThatPointAccount(TEST_USER_ID, Long.MAX_VALUE);
	}

	@Test
	void chargePoint_returnsInternalServerErrorWhenPointAccountIsMissing() throws Exception {
		mockMvc.perform(post("/api/v1/points/charges")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "userId": "point-missing-user",
							  "amount": 10000
							}
							"""))
				.andExpect(status().isInternalServerError())
				.andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"));
	}

	private void chargePoint(long amount) throws Exception {
		mockMvc.perform(post("/api/v1/points/charges")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "userId": "point-integration-user",
							  "amount": %d
							}
							""".formatted(amount)))
				.andExpect(status().isOk());
	}

	private void assertThatPointAccount(String userId, long expectedBalance) {
		Long balance = jdbcTemplate.queryForObject(
				"SELECT balance FROM point_accounts WHERE user_id = ?",
				Long.class,
				userId
		);

		org.assertj.core.api.Assertions.assertThat(balance).isEqualTo(expectedBalance);
	}

}
