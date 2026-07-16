package com.coffeepointordersystem.domain.order.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "orders")
public class Order {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false, length = 64)
	private String userId;

	@Column(name = "menu_id", nullable = false)
	private long menuId;

	@Column(name = "paid_amount", nullable = false)
	private long paidAmount;

	@Column(name = "ordered_at", nullable = false)
	private Instant orderedAt;

	protected Order() {
	}

	private Order(String userId, long menuId, long paidAmount, Instant orderedAt) {
		this.userId = userId;
		this.menuId = menuId;
		this.paidAmount = paidAmount;
		this.orderedAt = orderedAt;
	}

	public static Order create(String userId, long menuId, long paidAmount, Instant orderedAt) {
		return new Order(userId, menuId, paidAmount, orderedAt);
	}

	public Long getId() {
		return id;
	}

	public String getUserId() {
		return userId;
	}

	public long getMenuId() {
		return menuId;
	}

	public long getPaidAmount() {
		return paidAmount;
	}

	public Instant getOrderedAt() {
		return orderedAt;
	}

}
