package com.coffeepointordersystem.global.error;

import com.coffeepointordersystem.domain.menu.exception.MenuNotFoundException;
import com.coffeepointordersystem.domain.menu.exception.PopularMenuUnavailableException;
import com.coffeepointordersystem.domain.point.exception.InsufficientPointBalanceException;
import com.coffeepointordersystem.domain.point.exception.PointBalanceLimitExceededException;
import com.coffeepointordersystem.domain.point.exception.PointAccountNotFoundException;
import com.coffeepointordersystem.global.response.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler({
			MethodArgumentNotValidException.class,
			HttpMessageNotReadableException.class,
			HttpMediaTypeNotSupportedException.class,
			MissingRequestValueException.class
	})
	public ResponseEntity<ApiResponse<Void>> handleInvalidRequest(Exception exception) {
		return toResponseEntity(ErrorCode.INVALID_REQUEST);
	}

	@ExceptionHandler(PointBalanceLimitExceededException.class)
	public ResponseEntity<ApiResponse<Void>> handlePointBalanceLimitExceeded(
			PointBalanceLimitExceededException exception
	) {
		return toResponseEntity(ErrorCode.POINT_BALANCE_LIMIT_EXCEEDED);
	}

	@ExceptionHandler(MenuNotFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleMenuNotFound(MenuNotFoundException exception) {
		return toResponseEntity(ErrorCode.MENU_NOT_FOUND);
	}

	@ExceptionHandler(PopularMenuUnavailableException.class)
	public ResponseEntity<ApiResponse<Void>> handlePopularMenuUnavailable(PopularMenuUnavailableException exception) {
		return toResponseEntity(ErrorCode.POPULAR_MENU_UNAVAILABLE);
	}

	@ExceptionHandler(PointAccountNotFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handlePointAccountNotFound(PointAccountNotFoundException exception) {
		return toResponseEntity(ErrorCode.POINT_ACCOUNT_NOT_FOUND);
	}

	@ExceptionHandler(InsufficientPointBalanceException.class)
	public ResponseEntity<ApiResponse<Void>> handleInsufficientPointBalance(
			InsufficientPointBalanceException exception
	) {
		return toResponseEntity(ErrorCode.INSUFFICIENT_POINT_BALANCE);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleInternalServerError(Exception exception) {
		return toResponseEntity(ErrorCode.INTERNAL_SERVER_ERROR);
	}

	private ResponseEntity<ApiResponse<Void>> toResponseEntity(ErrorCode errorCode) {
		return ResponseEntity.status(errorCode.getHttpStatus())
				.body(ApiResponse.error(errorCode));
	}

}
