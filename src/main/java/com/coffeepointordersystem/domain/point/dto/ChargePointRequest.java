package com.coffeepointordersystem.domain.point.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record ChargePointRequest(
		@NotNull
		@Size(min = 1, max = 64)
		String userId,
		@NotNull
		@Positive
		Long amount
) {

	@AssertTrue
	public boolean isUserIdValid() {
		if (userId == null || userId.isBlank()) {
			return false;
		}

		return !Character.isWhitespace(userId.codePointAt(0))
				&& !Character.isWhitespace(userId.codePointBefore(userId.length()));
	}

}
