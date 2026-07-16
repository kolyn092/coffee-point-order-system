package com.coffeepointordersystem.domain.order.service;

import com.coffeepointordersystem.domain.menu.entity.Menu;
import com.coffeepointordersystem.domain.menu.exception.MenuNotFoundException;
import com.coffeepointordersystem.domain.menu.repository.MenuRepository;
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

@Service
public class CreateOrderApplicationService {

	private final MenuRepository menuRepository;
	private final PointAccountRepository pointAccountRepository;
	private final OrderRepository orderRepository;
	private final ApplicationEventPublisher applicationEventPublisher;
	private final Clock clock;

	public CreateOrderApplicationService(
			MenuRepository menuRepository,
			PointAccountRepository pointAccountRepository,
			OrderRepository orderRepository,
			ApplicationEventPublisher applicationEventPublisher,
			Clock clock
	) {
		this.menuRepository = menuRepository;
		this.pointAccountRepository = pointAccountRepository;
		this.orderRepository = orderRepository;
		this.applicationEventPublisher = applicationEventPublisher;
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
		applicationEventPublisher.publishEvent(OrderCompletedEvent.from(order));

		return OrderResponse.from(order, pointAccount);
	}

}
