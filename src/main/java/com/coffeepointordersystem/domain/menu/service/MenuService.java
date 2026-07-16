package com.coffeepointordersystem.domain.menu.service;

import com.coffeepointordersystem.domain.menu.dto.MenuListResponse;
import com.coffeepointordersystem.domain.menu.dto.PopularMenuResponse;
import com.coffeepointordersystem.domain.menu.entity.Menu;
import com.coffeepointordersystem.domain.menu.port.PopularMenuCache;
import com.coffeepointordersystem.domain.menu.repository.MenuRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Map;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MenuService {

	private final MenuRepository menuRepository;
	private final PopularMenuCache popularMenuCache;
	private final Clock clock;

	public MenuService(
			MenuRepository menuRepository,
			PopularMenuCache popularMenuCache,
			Clock clock
	) {
		this.menuRepository = menuRepository;
		this.popularMenuCache = popularMenuCache;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<MenuListResponse> findMenus() {
		return menuRepository.findAllByOrderByIdAsc()
				.stream()
				.map(MenuListResponse::from)
				.toList();
	}

	@Transactional(readOnly = true)
	public List<PopularMenuResponse> findPopularMenus() {
		LocalDate to = LocalDate.now(clock);
		Map<Long, Long> orderCounts = popularMenuCache.findOrderCounts(to.minusDays(6L), to);
		Map<Long, Menu> menusById = menuRepository.findAllById(orderCounts.keySet())
				.stream()
				.collect(Collectors.toMap(Menu::getId, Function.identity()));

		return orderCounts.entrySet()
				.stream()
				.sorted(Map.Entry.<Long, Long>comparingByValue().reversed()
						.thenComparing(Map.Entry.comparingByKey()))
				.filter(entry -> menusById.containsKey(entry.getKey()))
				.limit(3L)
				.map(entry -> PopularMenuResponse.from(menusById.get(entry.getKey()), entry.getValue()))
				.toList();
	}

}
