package com.coffeepointordersystem.infra.kafka;

import com.coffeepointordersystem.domain.menu.port.PopularMenuCache;
import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
public class PopularMenuKafkaConsumer {

	private final PopularMenuCache popularMenuCache;

	public PopularMenuKafkaConsumer(PopularMenuCache popularMenuCache) {
		this.popularMenuCache = popularMenuCache;
	}

	@KafkaListener(
			topics = OrderCompletedKafkaTopicConfig.ORDER_COMPLETED_TOPIC,
			containerFactory = "popularMenuKafkaListenerContainerFactory",
			autoStartup = "${popular-menu.consumer.enabled:true}"
	)
	public void consume(OrderCompletedEvent event, Acknowledgment acknowledgment) {
		popularMenuCache.recordCompletedOrder(event.orderId(), event.menuId(), event.occurredAt());
		acknowledgment.acknowledge();
	}

}
