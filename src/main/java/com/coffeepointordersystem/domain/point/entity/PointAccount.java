package com.coffeepointordersystem.domain.point.entity;

import com.coffeepointordersystem.domain.point.exception.PointBalanceLimitExceededException;
import com.coffeepointordersystem.domain.point.exception.InsufficientPointBalanceException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "point_accounts")
public class PointAccount {

	@Id
	@Column(name = "user_id", length = 64)
	private String userId;

	@Column(nullable = false)
	private long balance;

	protected PointAccount() {
	}

	PointAccount(String userId, long balance) {
		this.userId = userId;
		this.balance = balance;
	}

	public void charge(long amount) {
		try {
			balance = Math.addExact(balance, amount);
		} catch (ArithmeticException exception) {
			throw new PointBalanceLimitExceededException();
		}
	}

	public void use(long amount) {
		if (balance < amount) {
			throw new InsufficientPointBalanceException();
		}

		balance -= amount;
	}

	public String getUserId() {
		return userId;
	}

	public long getBalance() {
		return balance;
	}

}
