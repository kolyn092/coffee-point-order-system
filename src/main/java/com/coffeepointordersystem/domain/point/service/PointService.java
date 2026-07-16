package com.coffeepointordersystem.domain.point.service;

import com.coffeepointordersystem.domain.point.dto.PointChargeResponse;
import com.coffeepointordersystem.domain.point.entity.PointAccount;
import com.coffeepointordersystem.domain.point.repository.PointAccountRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PointService {

	private final PointAccountRepository pointAccountRepository;

	public PointService(PointAccountRepository pointAccountRepository) {
		this.pointAccountRepository = pointAccountRepository;
	}

	@Transactional
	public PointChargeResponse charge(String userId, long amount) {
		PointAccount pointAccount = pointAccountRepository.findByUserIdForUpdate(userId)
				.orElseThrow(() -> new IllegalStateException("포인트 계정을 찾을 수 없습니다."));
		pointAccount.charge(amount);

		return PointChargeResponse.from(pointAccount, amount);
	}

}
