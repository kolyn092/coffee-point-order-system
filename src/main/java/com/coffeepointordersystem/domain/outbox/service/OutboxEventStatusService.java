package com.coffeepointordersystem.domain.outbox.service;

import com.coffeepointordersystem.domain.outbox.entity.OutboxEvent;
import com.coffeepointordersystem.domain.outbox.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxEventStatusService {

	private final OutboxEventRepository outboxEventRepository;
	private final Clock clock;

	public OutboxEventStatusService(OutboxEventRepository outboxEventRepository, Clock clock) {
		this.outboxEventRepository = outboxEventRepository;
		this.clock = clock;
	}

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public void markPublished(long outboxEventId) {
		OutboxEvent outboxEvent = outboxEventRepository.findById(outboxEventId)
				.orElseThrow(() -> new IllegalStateException("Outbox 이벤트를 찾을 수 없습니다."));

		outboxEvent.markPublished(Instant.now(clock));
	}

}
