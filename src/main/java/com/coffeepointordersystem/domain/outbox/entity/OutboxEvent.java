package com.coffeepointordersystem.domain.outbox.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "order_id", nullable = false, unique = true)
	private long orderId;

	@JdbcTypeCode(SqlTypes.JSON)
	@Column(nullable = false, columnDefinition = "json")
	private String payload;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private OutboxEventStatus status;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "published_at")
	private Instant publishedAt;

	protected OutboxEvent() {
	}

	private OutboxEvent(long orderId, String payload, Instant createdAt) {
		this.orderId = orderId;
		this.payload = payload;
		this.status = OutboxEventStatus.PENDING;
		this.createdAt = createdAt.truncatedTo(ChronoUnit.MICROS);
	}

	public static OutboxEvent pending(long orderId, String payload, Instant createdAt) {
		return new OutboxEvent(orderId, payload, createdAt);
	}

	public void markPublished(Instant publishedAt) {
		if (status == OutboxEventStatus.PUBLISHED) {
			return;
		}

		status = OutboxEventStatus.PUBLISHED;
		this.publishedAt = publishedAt.truncatedTo(ChronoUnit.MICROS);
	}

	public Long getId() {
		return id;
	}

	public long getOrderId() {
		return orderId;
	}

	public String getPayload() {
		return payload;
	}

	public OutboxEventStatus getStatus() {
		return status;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public Instant getPublishedAt() {
		return publishedAt;
	}

}
