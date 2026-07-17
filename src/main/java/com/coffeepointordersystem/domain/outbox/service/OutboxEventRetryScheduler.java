package com.coffeepointordersystem.domain.outbox.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class OutboxEventRetryScheduler {

	private static final Logger log = LoggerFactory.getLogger(OutboxEventRetryScheduler.class);

	private final OutboxEventRetryService outboxEventRetryService;

	public OutboxEventRetryScheduler(OutboxEventRetryService outboxEventRetryService) {
		this.outboxEventRetryService = outboxEventRetryService;
	}

	/**
	 * PENDING 이벤트를 하나씩 재시도하고, 실패한 이벤트는 다음 주기에 다시 시도한다.
	 */
	@Scheduled(fixedDelayString = "${outbox.retry.fixed-delay}")
	public void retryPendingEvents() {
		try {
			while (outboxEventRetryService.publishNextPendingEvent()) {
			}
		} catch (RuntimeException exception) {
			log.warn("PENDING Outbox 이벤트 재시도에 실패했습니다.", exception);
		}
	}

}
