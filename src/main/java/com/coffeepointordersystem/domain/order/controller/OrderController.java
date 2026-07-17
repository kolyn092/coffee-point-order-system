package com.coffeepointordersystem.domain.order.controller;

import com.coffeepointordersystem.domain.order.dto.CreateOrderRequest;
import com.coffeepointordersystem.domain.order.dto.OrderResponse;
import com.coffeepointordersystem.domain.order.service.CreateOrderApplicationService;
import com.coffeepointordersystem.global.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

	private final CreateOrderApplicationService createOrderApplicationService;

	public OrderController(CreateOrderApplicationService createOrderApplicationService) {
		this.createOrderApplicationService = createOrderApplicationService;
	}

	@PostMapping
	public ResponseEntity<ApiResponse<OrderResponse>> createOrder(@Valid @RequestBody CreateOrderRequest request) {
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.ok(createOrderApplicationService.create(request.userId(), request.menuId())));
	}

}
