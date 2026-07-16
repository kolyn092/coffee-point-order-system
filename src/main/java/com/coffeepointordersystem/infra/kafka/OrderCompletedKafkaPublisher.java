package com.coffeepointordersystem.infra.kafka;

import com.coffeepointordersystem.domain.outbox.event.OrderCompletedOutboxEvent;
import com.coffeepointordersystem.domain.outbox.service.OutboxEventStatusService;
import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class OrderCompletedKafkaPublisher {

	private static final Logger log = LoggerFactory.getLogger(OrderCompletedKafkaPublisher.class);
	private static final String ORDER_COMPLETED_TOPIC = "order.completed";

	private final KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate;
	private final OutboxEventStatusService outboxEventStatusService;

	public OrderCompletedKafkaPublisher(
			KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate,
			OutboxEventStatusService outboxEventStatusService
	) {
		this.kafkaTemplate = kafkaTemplate;
		this.outboxEventStatusService = outboxEventStatusService;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void publish(OrderCompletedOutboxEvent outboxEvent) {
		try {
			kafkaTemplate.send(
						ORDER_COMPLETED_TOPIC,
						Long.toString(outboxEvent.orderCompletedEvent().orderId()),
						outboxEvent.orderCompletedEvent()
				)
					.whenComplete((result, exception) -> handlePublishCompletion(outboxEvent, exception));
		} catch (RuntimeException exception) {
			logPublishFailure(outboxEvent, exception);
		}
	}

	private void handlePublishCompletion(OrderCompletedOutboxEvent outboxEvent, Throwable exception) {
		if (exception != null) {
			logPublishFailure(outboxEvent, exception);
			return;
		}

		try {
			outboxEventStatusService.markPublished(outboxEvent.outboxEventId());
		} catch (RuntimeException statusTransitionException) {
			log.warn(
					"Outbox 이벤트 발행 상태 전이에 실패했습니다. outboxEventId={}, orderId={}",
					outboxEvent.outboxEventId(),
					outboxEvent.orderCompletedEvent().orderId(),
					statusTransitionException
			);
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
