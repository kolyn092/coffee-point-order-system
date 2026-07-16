package com.coffeepointordersystem.domain.point.dto;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ChargePointRequestTest {

	@Test
	void isUserIdValid_accepts64SupplementaryCharacters() {
		String userId = "\uD83D\uDE00".repeat(64);
		ChargePointRequest request = new ChargePointRequest(userId, 1L);

		assertThat(request.isUserIdValid()).isTrue();
	}

	@Test
	void isUserIdValid_rejects65SupplementaryCharacters() {
		String userId = "\uD83D\uDE00".repeat(65);
		ChargePointRequest request = new ChargePointRequest(userId, 1L);

		assertThat(request.isUserIdValid()).isFalse();
	}

}
