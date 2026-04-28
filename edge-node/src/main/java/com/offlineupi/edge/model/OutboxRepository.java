package com.offlineupi.edge.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface OutboxRepository extends JpaRepository<OutboxPacket, Long> {

    /** Find packets ready to retry right now */
    List<OutboxPacket> findByStatusAndNextRetryAtBefore(String status, Instant now);

    List<OutboxPacket> findByStatusOrderByCreatedAtDesc(String status);

    List<OutboxPacket> findTop20ByOrderByCreatedAtDesc();

    long countByStatus(String status);
}
