package com.coffeepointordersystem.domain.point.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record ChargePointRequest(
		@NotNull
		String userId,
		@NotNull
		@Positive
		Long amount
) {

	@AssertTrue
	public boolean isUserIdValid() {
		if (userId == null || userId.isBlank() || userId.codePointCount(0, userId.length()) > 64) {
			return false;
		}

		return !Character.isWhitespace(userId.codePointAt(0))
				&& !Character.isWhitespace(userId.codePointBefore(userId.length()));
	}

}
