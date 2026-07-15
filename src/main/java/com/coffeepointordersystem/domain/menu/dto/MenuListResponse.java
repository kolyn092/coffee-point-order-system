package com.coffeepointordersystem.domain.menu.dto;

import com.coffeepointordersystem.domain.menu.entity.Menu;

public record MenuListResponse(
		Long menuId,
		String name,
		long price
) {

	public static MenuListResponse from(Menu menu) {
		return new MenuListResponse(menu.getId(), menu.getName(), menu.getPrice());
	}

}
