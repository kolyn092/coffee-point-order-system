package com.coffeepointordersystem.global.error;

import org.springframework.http.HttpStatus;

public enum ErrorCode {

	INVALID_REQUEST(HttpStatus.BAD_REQUEST, "요청 값이 올바르지 않습니다."),
	MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "메뉴를 찾을 수 없습니다."),
	POINT_ACCOUNT_NOT_FOUND(HttpStatus.NOT_FOUND, "포인트 계정을 찾을 수 없습니다."),
	INSUFFICIENT_POINT_BALANCE(HttpStatus.CONFLICT, "포인트 잔액이 부족합니다."),
	POINT_BALANCE_LIMIT_EXCEEDED(HttpStatus.CONFLICT, "포인트 잔액 한도를 초과했습니다."),
	INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

	private final HttpStatus httpStatus;
	private final String message;

	ErrorCode(HttpStatus httpStatus, String message) {
		this.httpStatus = httpStatus;
		this.message = message;
	}

	public HttpStatus getHttpStatus() {
		return httpStatus;
	}

	public String getMessage() {
		return message;
	}

}
