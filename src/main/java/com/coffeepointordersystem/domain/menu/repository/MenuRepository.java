package com.coffeepointordersystem.domain.menu.repository;

import com.coffeepointordersystem.domain.menu.entity.Menu;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuRepository extends JpaRepository<Menu, Long> {

	List<Menu> findAllByOrderByIdAsc();

}
