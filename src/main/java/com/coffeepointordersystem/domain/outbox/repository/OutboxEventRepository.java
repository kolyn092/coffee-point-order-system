package com.coffeepointordersystem.domain.outbox.repository;

import com.coffeepointordersystem.domain.outbox.entity.OutboxEvent;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

	@Query(value = """
			SELECT *
			FROM outbox_events FORCE INDEX (idx_outbox_events_status_id)
			WHERE status = 'PENDING'
			ORDER BY id ASC
			LIMIT 1
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	Optional<OutboxEvent> findFirstPendingForUpdate();

	@Query(value = """
			SELECT *
			FROM outbox_events
			WHERE id = :outboxEventId
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	Optional<OutboxEvent> findByIdForUpdate(@Param("outboxEventId") long outboxEventId);

}
