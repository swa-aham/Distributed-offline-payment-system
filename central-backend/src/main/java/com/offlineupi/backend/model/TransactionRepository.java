package com.offlineupi.backend.model;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Optional<Transaction> findByPacketHash(String packetHash);

    List<Transaction> findTop20ByOrderBySettledAtDesc();

    long countByOutcome(String outcome);
}
