package com.coffeepointordersystem.domain.outbox.service;

import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import com.coffeepointordersystem.domain.outbox.entity.OutboxEvent;
import com.coffeepointordersystem.domain.outbox.entity.OutboxEventStatus;
import com.coffeepointordersystem.domain.outbox.port.OrderCompletedEventPublisher;
import com.coffeepointordersystem.domain.outbox.repository.OutboxEventRepository;
import com.coffeepointordersystem.global.config.OutboxRetryProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class OutboxEventRetryService {

	private final OutboxEventRepository outboxEventRepository;
	private final OrderCompletedEventPublisher orderCompletedEventPublisher;
	private final OutboxRetryProperties outboxRetryProperties;
	private final JsonMapper jsonMapper;
	private final Clock clock;

	public OutboxEventRetryService(
			OutboxEventRepository outboxEventRepository,
			OrderCompletedEventPublisher orderCompletedEventPublisher,
			OutboxRetryProperties outboxRetryProperties,
			JsonMapper jsonMapper,
			Clock clock
	) {
		this.outboxEventRepository = outboxEventRepository;
		this.orderCompletedEventPublisher = orderCompletedEventPublisher;
		this.outboxRetryProperties = outboxRetryProperties;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
	}

	/**
	 * ID 오름차순의 다음 PENDING Outbox 이벤트를 행 잠금으로 소유하고 재시도한다.
	 *
	 * @return 후보 행이 존재하면 {@code true}, PENDING 행이 없으면 {@code false}
	 * @throws IllegalStateException payload 복원, Kafka 발행 또는 상태 전이에 실패한 경우
	 */
	@Transactional
	public boolean publishNextPendingEvent() {
		return outboxEventRepository.findFirstPendingForUpdate()
				.map(outboxEvent -> {
					publishIfPending(outboxEvent);
					return true;
				})
				.orElse(false);
	}

	/**
	 * 주문 commit 후 최초 발행할 Outbox 이벤트를 행 잠금으로 소유하고, 다른 게시자가 잠근 이벤트는 건너뛴다.
	 *
	 * @param outboxEventId 최초 발행할 Outbox 이벤트 식별자
	 * @throws IllegalStateException payload 복원, Kafka 발행 또는 상태 전이에 실패한 경우
	 */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void publishPendingEvent(long outboxEventId) {
		outboxEventRepository.findByIdForUpdate(outboxEventId)
				.ifPresent(this::publishIfPending);
	}

	private void publishIfPending(OutboxEvent outboxEvent) {
		if (outboxEvent.getStatus() != OutboxEventStatus.PENDING) {
			return;
		}

		OrderCompletedEvent orderCompletedEvent = deserialize(outboxEvent.getPayload());
		orderCompletedEventPublisher.publish(
				orderCompletedEvent,
				Duration.ofMillis(outboxRetryProperties.publishTimeoutMillis())
		);
		outboxEvent.markPublished(Instant.now(clock));
	}

	private OrderCompletedEvent deserialize(String payload) {
		try {
			return jsonMapper.readValue(payload, OrderCompletedEvent.class);
		} catch (JacksonException exception) {
			throw new IllegalStateException("Outbox 이벤트 payload를 역직렬화할 수 없습니다.", exception);
		}
	}

}
