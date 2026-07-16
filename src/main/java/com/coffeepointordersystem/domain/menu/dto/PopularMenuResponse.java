package com.coffeepointordersystem.domain.menu.dto;

import com.coffeepointordersystem.domain.menu.entity.Menu;

public record PopularMenuResponse(
		Long menuId,
		String name,
		long price,
		long orderCount
) {

	public static PopularMenuResponse from(Menu menu, long orderCount) {
		return new PopularMenuResponse(menu.getId(), menu.getName(), menu.getPrice(), orderCount);
	}

}
