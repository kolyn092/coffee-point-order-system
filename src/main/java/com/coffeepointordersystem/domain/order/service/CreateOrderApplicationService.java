package com.coffeepointordersystem.domain.order.service;

import com.coffeepointordersystem.domain.menu.entity.Menu;
import com.coffeepointordersystem.domain.menu.exception.MenuNotFoundException;
import com.coffeepointordersystem.domain.menu.repository.MenuRepository;
import com.coffeepointordersystem.domain.outbox.entity.OutboxEvent;
import com.coffeepointordersystem.domain.outbox.event.OrderCompletedOutboxEvent;
import com.coffeepointordersystem.domain.outbox.repository.OutboxEventRepository;
import com.coffeepointordersystem.domain.order.dto.OrderResponse;
import com.coffeepointordersystem.domain.order.entity.Order;
import com.coffeepointordersystem.domain.order.event.OrderCompletedEvent;
import com.coffeepointordersystem.domain.order.repository.OrderRepository;
import com.coffeepointordersystem.domain.point.entity.PointAccount;
import com.coffeepointordersystem.domain.point.exception.PointAccountNotFoundException;
import com.coffeepointordersystem.domain.point.repository.PointAccountRepository;
import java.time.Clock;
import java.time.Instant;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class CreateOrderApplicationService {

	private final MenuRepository menuRepository;
	private final PointAccountRepository pointAccountRepository;
	private final OrderRepository orderRepository;
	private final OutboxEventRepository outboxEventRepository;
	private final ApplicationEventPublisher applicationEventPublisher;
	private final JsonMapper jsonMapper;
	private final Clock clock;

	public CreateOrderApplicationService(
			MenuRepository menuRepository,
			PointAccountRepository pointAccountRepository,
			OrderRepository orderRepository,
			OutboxEventRepository outboxEventRepository,
			ApplicationEventPublisher applicationEventPublisher,
			JsonMapper jsonMapper,
			Clock clock
	) {
		this.menuRepository = menuRepository;
		this.pointAccountRepository = pointAccountRepository;
		this.orderRepository = orderRepository;
		this.outboxEventRepository = outboxEventRepository;
		this.applicationEventPublisher = applicationEventPublisher;
		this.jsonMapper = jsonMapper;
		this.clock = clock;
	}

	@Transactional
	public OrderResponse create(String userId, long menuId) {
		Menu menu = menuRepository.findById(menuId)
				.orElseThrow(MenuNotFoundException::new);
		PointAccount pointAccount = pointAccountRepository.findByUserIdForUpdate(userId)
				.orElseThrow(PointAccountNotFoundException::new);

		pointAccount.use(menu.getPrice());
		Order order = orderRepository.save(Order.create(userId, menuId, menu.getPrice(), Instant.now(clock)));
		OrderCompletedEvent orderCompletedEvent = OrderCompletedEvent.from(order);
		OutboxEvent outboxEvent = outboxEventRepository.save(
				OutboxEvent.pending(order.getId(), serialize(orderCompletedEvent), order.getOrderedAt())
		);
		applicationEventPublisher.publishEvent(new OrderCompletedOutboxEvent(outboxEvent.getId(), orderCompletedEvent));

		return OrderResponse.from(order, pointAccount);
	}

	private String serialize(OrderCompletedEvent orderCompletedEvent) {
		try {
			return jsonMapper.writeValueAsString(orderCompletedEvent);
		} catch (JacksonException exception) {
			throw new IllegalStateException("주문 완료 이벤트를 직렬화할 수 없습니다.", exception);
		}
	}

}
