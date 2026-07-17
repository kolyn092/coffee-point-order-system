package com.coffeepointordersystem.infra.kafka;

import com.coffeepointordersystem.domain.outbox.event.OrderCompletedOutboxEvent;
import com.coffeepointordersystem.domain.outbox.service.OutboxEventRetryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class OrderCompletedKafkaPublisher {

	private static final Logger log = LoggerFactory.getLogger(OrderCompletedKafkaPublisher.class);

	private final OutboxEventRetryService outboxEventRetryService;

	public OrderCompletedKafkaPublisher(OutboxEventRetryService outboxEventRetryService) {
		this.outboxEventRetryService = outboxEventRetryService;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void publish(OrderCompletedOutboxEvent outboxEvent) {
		try {
			outboxEventRetryService.publishPendingEvent(outboxEvent.outboxEventId());
		} catch (RuntimeException exception) {
			logPublishFailure(outboxEvent, exception);
		}
	}

	private void logPublishFailure(OrderCompletedOutboxEvent outboxEvent, Throwable exception) {
		log.warn(
				"주문 완료 이벤트 발행에 실패했습니다. outboxEventId={}, orderId={}",
				outboxEvent.outboxEventId(),
				outboxEvent.orderCompletedEvent().orderId(),
				exception
		);
	}

}
