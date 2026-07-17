package com.coffeepointordersystem.infra.kafka;

import com.coffeepointordersystem.domain.menu.port.PopularMenuCache;
import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class PopularMenuKafkaConsumer {

	private static final String ORDER_COMPLETED_TOPIC = "order.completed";

	private final PopularMenuCache popularMenuCache;

	public PopularMenuKafkaConsumer(PopularMenuCache popularMenuCache) {
		this.popularMenuCache = popularMenuCache;
	}

	@KafkaListener(topics = ORDER_COMPLETED_TOPIC, containerFactory = "popularMenuKafkaListenerContainerFactory")
	public void consume(OrderCompletedEvent event, Acknowledgment acknowledgment) {
		popularMenuCache.recordCompletedOrder(event.orderId(), event.menuId(), event.occurredAt());
		acknowledgment.acknowledge();
	}

}
