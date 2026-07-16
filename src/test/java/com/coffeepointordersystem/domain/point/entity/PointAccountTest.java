package com.coffeepointordersystem.domain.point.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coffeepointordersystem.domain.point.exception.PointBalanceLimitExceededException;
import com.coffeepointordersystem.domain.point.exception.InsufficientPointBalanceException;
import org.junit.jupiter.api.Test;

class PointAccountTest {

	@Test
	void charge_throwsExceptionAndPreservesBalanceWhenAdditionOverflows() {
		PointAccount pointAccount = new PointAccount("point-unit-user", Long.MAX_VALUE);

		assertThatThrownBy(() -> pointAccount.charge(1L))
				.isInstanceOf(PointBalanceLimitExceededException.class);
		assertThat(pointAccount.getBalance()).isEqualTo(Long.MAX_VALUE);
	}

	@Test
	void use_throwsExceptionAndPreservesBalanceWhenBalanceIsInsufficient() {
		PointAccount pointAccount = new PointAccount("point-unit-user", 4_499L);

		assertThatThrownBy(() -> pointAccount.use(4_500L))
				.isInstanceOf(InsufficientPointBalanceException.class);
		assertThat(pointAccount.getBalance()).isEqualTo(4_499L);
	}

}
