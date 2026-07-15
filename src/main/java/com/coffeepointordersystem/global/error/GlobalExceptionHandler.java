package com.coffeepointordersystem.global.error;

import com.coffeepointordersystem.domain.point.exception.PointBalanceLimitExceededException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;

@RestControllerAdvice
public class GlobalExceptionHandler {

	@ExceptionHandler({
			MethodArgumentNotValidException.class,
			HttpMessageNotReadableException.class,
			MissingRequestValueException.class
	})
	public ResponseEntity<ErrorResponse> handleInvalidRequest(Exception exception) {
		return toResponseEntity(ErrorCode.INVALID_REQUEST);
	}

	@ExceptionHandler(PointBalanceLimitExceededException.class)
	public ResponseEntity<ErrorResponse> handlePointBalanceLimitExceeded(
			PointBalanceLimitExceededException exception
	) {
		return toResponseEntity(ErrorCode.POINT_BALANCE_LIMIT_EXCEEDED);
	}

	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleInternalServerError(Exception exception) {
		return toResponseEntity(ErrorCode.INTERNAL_SERVER_ERROR);
	}

	private ResponseEntity<ErrorResponse> toResponseEntity(ErrorCode errorCode) {
		return ResponseEntity.status(errorCode.getHttpStatus())
				.body(ErrorResponse.from(errorCode));
	}

}
