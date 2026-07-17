package com.coffeepointordersystem.domain.menu;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
class MenuIntegrationTest {

	private static final long FIRST_MENU_ID = 10L;
	private static final long SECOND_MENU_ID = 20L;
	private static final long THIRD_MENU_ID = 30L;

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
	void clearMenus() {
		jdbcTemplate.update("DELETE FROM menus");
	}

	@Test
	void getMenus_returnsOkWithResponseFields() throws Exception {
		insertMenu(FIRST_MENU_ID, "아메리카노", 4500L);

		mockMvc.perform(get("/api/v1/menus"))
				.andExpect(status().isOk())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data[0].menuId").value(FIRST_MENU_ID))
				.andExpect(jsonPath("$.data[0].name").value("아메리카노"))
				.andExpect(jsonPath("$.data[0].price").value(4500));
	}

	@Test
	void getMenus_returnsMenusInAscendingMenuIdOrder() throws Exception {
		insertMenu(THIRD_MENU_ID, "카푸치노", 5500L);
		insertMenu(FIRST_MENU_ID, "아메리카노", 4500L);
		insertMenu(SECOND_MENU_ID, "카페라떼", 5000L);

		mockMvc.perform(get("/api/v1/menus"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data[0].menuId").value(FIRST_MENU_ID))
				.andExpect(jsonPath("$.data[1].menuId").value(SECOND_MENU_ID))
				.andExpect(jsonPath("$.data[2].menuId").value(THIRD_MENU_ID));
	}

	@Test
	void getMenus_returnsEmptyArrayWhenNoMenusExist() throws Exception {
		mockMvc.perform(get("/api/v1/menus"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("SUCCESS"))
				.andExpect(jsonPath("$.data").isArray())
				.andExpect(jsonPath("$.data").isEmpty());
	}

	private void insertMenu(long menuId, String name, long price) {
		jdbcTemplate.update(
				"INSERT INTO menus (id, name, price) VALUES (?, ?, ?)",
				menuId,
				name,
				price
		);
	}

}
