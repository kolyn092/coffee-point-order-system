package com.coffeepointordersystem.infra.kafka;

import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import com.coffeepointordersystem.domain.outbox.port.OrderCompletedEventPublisher;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class KafkaOrderCompletedEventPublisher implements OrderCompletedEventPublisher {

	private static final String ORDER_COMPLETED_TOPIC = "order.completed";

	private final KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate;

	public KafkaOrderCompletedEventPublisher(KafkaTemplate<String, OrderCompletedEvent> kafkaTemplate) {
		this.kafkaTemplate = kafkaTemplate;
	}

	@Override
	public void publish(OrderCompletedEvent orderCompletedEvent, Duration timeout) {
		try {
			long deadline = System.nanoTime() + timeout.toNanos();
			var sendResult = kafkaTemplate.send(
						ORDER_COMPLETED_TOPIC,
						Long.toString(orderCompletedEvent.orderId()),
						orderCompletedEvent
				);
			long remainingNanos = deadline - System.nanoTime();
			if (remainingNanos <= 0L) {
				throw new TimeoutException("Kafka 발행 시작 시간이 제한을 초과했습니다.");
			}

			sendResult.get(remainingNanos, TimeUnit.NANOSECONDS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Kafka 발행 완료 대기 중 인터럽트가 발생했습니다.", exception);
		} catch (ExecutionException | TimeoutException exception) {
			throw new IllegalStateException("Kafka 발행에 실패했거나 제한 시간 안에 완료되지 않았습니다.", exception);
		}
	}

}
