package com.coffeepointordersystem.domain.point.controller;

import com.coffeepointordersystem.domain.point.dto.ChargePointRequest;
import com.coffeepointordersystem.domain.point.dto.PointChargeResponse;
import com.coffeepointordersystem.domain.point.service.PointService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/points")
public class PointController {

	private final PointService pointService;

	public PointController(PointService pointService) {
		this.pointService = pointService;
	}

	@PostMapping("/charges")
	public PointChargeResponse chargePoint(@Valid @RequestBody ChargePointRequest request) {
		return pointService.charge(request.userId(), request.amount());
	}

}
