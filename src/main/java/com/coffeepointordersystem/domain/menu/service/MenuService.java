package com.coffeepointordersystem.domain.menu.service;

import com.coffeepointordersystem.domain.menu.dto.MenuListResponse;
import com.coffeepointordersystem.domain.menu.repository.MenuRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MenuService {

	private final MenuRepository menuRepository;

	public MenuService(MenuRepository menuRepository) {
		this.menuRepository = menuRepository;
	}

	@Transactional(readOnly = true)
	public List<MenuListResponse> findMenus() {
		return menuRepository.findAllByOrderByIdAsc()
				.stream()
				.map(MenuListResponse::from)
				.toList();
	}

}
