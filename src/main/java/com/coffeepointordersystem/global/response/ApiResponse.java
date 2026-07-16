package com.coffeepointordersystem.global.response;

import com.coffeepointordersystem.global.error.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(
		String code,
		String message,
		T data
) {

	private static final String SUCCESS_CODE = "SUCCESS";

	public static <T> ApiResponse<T> ok(T data) {
		return new ApiResponse<>(SUCCESS_CODE, null, data);
	}

	public static ApiResponse<Void> ok() {
		return new ApiResponse<>(SUCCESS_CODE, null, null);
	}

	public static ApiResponse<Void> error(ErrorCode errorCode) {
		return new ApiResponse<>(errorCode.name(), errorCode.getMessage(), null);
	}

	public static ApiResponse<Void> error(ErrorCode errorCode, String message) {
		return new ApiResponse<>(errorCode.name(), message, null);
	}

	public static ApiResponse<Void> error(String code, String message) {
		return new ApiResponse<>(code, message, null);
	}

}
