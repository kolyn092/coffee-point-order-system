package com.coffeepointordersystem.domain.order.repository;

import com.coffeepointordersystem.domain.order.entity.Order;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

}
