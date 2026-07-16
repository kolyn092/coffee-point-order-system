package com.coffeepointordersystem.domain.menu.controller;

import com.coffeepointordersystem.domain.menu.dto.MenuListResponse;
import com.coffeepointordersystem.domain.menu.dto.PopularMenuResponse;
import com.coffeepointordersystem.domain.menu.service.MenuService;
import com.coffeepointordersystem.global.response.ApiResponse;
import java.util.List;
import org.springframework.http.ResponseEntity;
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
	public ResponseEntity<ApiResponse<List<MenuListResponse>>> findMenus() {
		return ResponseEntity.ok(ApiResponse.ok(menuService.findMenus()));
	}

	@GetMapping("/popular")
	public ResponseEntity<ApiResponse<List<PopularMenuResponse>>> findPopularMenus() {
		return ResponseEntity.ok(ApiResponse.ok(menuService.findPopularMenus()));
	}

}
