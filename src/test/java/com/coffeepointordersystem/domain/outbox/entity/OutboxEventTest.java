package com.coffeepointordersystem.domain.outbox.entity;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class OutboxEventTest {

	@Test
	void pending_createsPendingEventWithMicrosecondTimestamp() {
		Instant createdAt = Instant.parse("2026-07-16T01:02:03.123456789Z");

		OutboxEvent outboxEvent = OutboxEvent.pending(10L, "{}", createdAt);

		assertThat(outboxEvent.getOrderId()).isEqualTo(10L);
		assertThat(outboxEvent.getPayload()).isEqualTo("{}");
		assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PENDING);
		assertThat(outboxEvent.getCreatedAt()).isEqualTo(Instant.parse("2026-07-16T01:02:03.123456Z"));
		assertThat(outboxEvent.getPublishedAt()).isNull();
	}

	@Test
	void markPublished_changesStatusAndRecordsPublishedAt() {
		OutboxEvent outboxEvent = OutboxEvent.pending(
				10L,
				"{}",
				Instant.parse("2026-07-16T01:02:03.123456Z")
		);

		outboxEvent.markPublished(Instant.parse("2026-07-16T01:03:04.987654321Z"));

		assertThat(outboxEvent.getStatus()).isEqualTo(OutboxEventStatus.PUBLISHED);
		assertThat(outboxEvent.getPublishedAt()).isEqualTo(Instant.parse("2026-07-16T01:03:04.987654Z"));
	}

}
