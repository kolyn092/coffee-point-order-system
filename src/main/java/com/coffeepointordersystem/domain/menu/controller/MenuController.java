package com.coffeepointordersystem.domain.menu.controller;

import com.coffeepointordersystem.domain.menu.dto.MenuListResponse;
import com.coffeepointordersystem.domain.menu.dto.PopularMenuResponse;
import com.coffeepointordersystem.domain.menu.service.MenuService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/menus")
public class MenuController {

	private final MenuService menuService;

	public MenuController(MenuService menuService) {
		this.menuService = menuService;
	}

	@GetMapping
	public List<MenuListResponse> findMenus() {
		return menuService.findMenus();
	}

	@GetMapping("/popular")
	public List<PopularMenuResponse> findPopularMenus() {
		return menuService.findPopularMenus();
	}

}
