package com.coffeepointordersystem.domain.outbox.port;

import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import java.time.Duration;

public interface OrderCompletedEventPublisher {

	/**
	 * 주문 완료 이벤트의 Kafka 발행 완료를 주어진 시간만큼 기다린다.
	 *
	 * @param orderCompletedEvent 저장된 Outbox payload에서 복원한 주문 완료 이벤트
	 * @param timeout 발행 완료를 기다리는 최대 시간
	 * @throws IllegalStateException 발행 실패, 시간 초과 또는 인터럽트가 발생한 경우
	 */
	void publish(OrderCompletedEvent orderCompletedEvent, Duration timeout);

}
