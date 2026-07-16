package com.coffeepointordersystem.infra.kafka;

import com.coffeepointordersystem.domain.menu.port.PopularMenuCache;
import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PopularMenuKafkaConsumer {

	private static final String ORDER_COMPLETED_TOPIC = "order.completed";

	private final PopularMenuCache popularMenuCache;

	public PopularMenuKafkaConsumer(PopularMenuCache popularMenuCache) {
		this.popularMenuCache = popularMenuCache;
	}

	@KafkaListener(topics = ORDER_COMPLETED_TOPIC)
	public void consume(OrderCompletedEvent event) {
		popularMenuCache.incrementOrderCount(event.menuId(), event.occurredAt());
	}

}
