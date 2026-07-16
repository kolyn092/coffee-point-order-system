package com.coffeepointordersystem.global.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.coffeepointordersystem.global.error.ErrorCode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ApiResponseTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	@Test
	void ok_withDataSerializesCodeAndDataWithoutMessage() throws Exception {
		JsonNode response = objectMapper.readTree(
				objectMapper.writeValueAsString(ApiResponse.ok(Map.of("menuId", 1L)))
		);

		assertThat(response.path("code").asText()).isEqualTo("SUCCESS");
		assertThat(response.path("data").path("menuId").asLong()).isEqualTo(1L);
		assertThat(response.has("message")).isFalse();
	}

	@Test
	void ok_withoutDataSerializesOnlyCode() throws Exception {
		JsonNode response = objectMapper.readTree(objectMapper.writeValueAsString(ApiResponse.ok()));

		assertThat(response.path("code").asText()).isEqualTo("SUCCESS");
		assertThat(response.has("message")).isFalse();
		assertThat(response.has("data")).isFalse();
	}

	@Test
	void error_serializesCodeAndMessageWithoutData() throws Exception {
		JsonNode response = objectMapper.readTree(
				objectMapper.writeValueAsString(ApiResponse.error(ErrorCode.INVALID_REQUEST))
		);

		assertThat(response.path("code").asText()).isEqualTo("INVALID_REQUEST");
		assertThat(response.path("message").asText()).isEqualTo("요청 값이 올바르지 않습니다.");
		assertThat(response.has("data")).isFalse();
	}

}
