package com.coffeepointordersystem.domain.outbox.repository;

import com.coffeepointordersystem.domain.outbox.entity.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

}
