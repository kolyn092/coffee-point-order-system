package com.coffeepointordersystem.domain.point.dto;

import com.coffeepointordersystem.domain.point.entity.PointAccount;

public record PointChargeResponse(
		String userId,
		long chargedAmount,
		long balance
) {

	public static PointChargeResponse from(PointAccount pointAccount, long chargedAmount) {
		return new PointChargeResponse(
				pointAccount.getUserId(),
				chargedAmount,
				pointAccount.getBalance()
		);
	}

}
