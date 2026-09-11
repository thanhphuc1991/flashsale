package com.flashsale.flashsale.repository;

import com.flashsale.flashsale.domain.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    @Query("SELECT e FROM OutboxEvent e WHERE e.status = 'PENDING' ORDER BY e.id ASC")
    List<OutboxEvent> findPendingBatch(org.springframework.data.domain.Pageable pageable);
}
