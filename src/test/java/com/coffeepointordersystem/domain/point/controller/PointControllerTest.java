package com.coffeepointordersystem.domain.point.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coffeepointordersystem.domain.point.service.PointService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(PointController.class)
class PointControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private PointService pointService;

	@Test
	void chargePoint_rejectsNumericUserIdWithoutCallingService() throws Exception {
		mockMvc.perform(post("/api/v1/points/charges")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "userId": 123,
							  "amount": 10000
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		verifyNoInteractions(pointService);
	}

	@Test
	void chargePoint_rejectsStringAmountWithoutCallingService() throws Exception {
		mockMvc.perform(post("/api/v1/points/charges")
					.contentType(MediaType.APPLICATION_JSON)
					.content("""
							{
							  "userId": "123",
							  "amount": "10000"
							}
							"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

		verifyNoInteractions(pointService);
	}

}
