package com.coffeepointordersystem.infra.kafka;

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

	public OrderCompletedKafkaPublisher(KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate) {
		this.kafkaTemplate = kafkaTemplate;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void publish(OrderCompletedEvent event) {
		try {
			kafkaTemplate.send(ORDER_COMPLETED_TOPIC, Long.toString(event.orderId()), event)
					.whenComplete((result, exception) -> logPublishFailure(event, exception));
		} catch (RuntimeException exception) {
			logPublishFailure(event, exception);
		}
	}

	private void logPublishFailure(OrderCompletedEvent event, Throwable exception) {
		if (exception == null) {
			return;
		}

		log.warn("주문 완료 이벤트 발행에 실패했습니다. orderId={}", event.orderId(), exception);
	}

}
