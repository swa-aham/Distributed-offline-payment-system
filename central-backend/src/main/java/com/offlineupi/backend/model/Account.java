package com.offlineupi.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "accounts")
@Getter @Setter @NoArgsConstructor
public class Account {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String vpa;

    @Column(name = "holder_name", nullable = false)
    private String holderName;

    /**
     * Balance stored in paise (1 rupee = 100 paise).
     * Storing as integer avoids floating-point issues.
     */
    @Column(name = "balance_paise", nullable = false)
    private Long balancePaise;

    /**
     * Optimistic locking — if two threads try to update the same account
     * simultaneously, one will get an OptimisticLockException.
     * This is the defense-in-depth layer below Redis idempotency.
     */
    @Version
    private Long version;

    @Column(name = "created_at")
    private Instant createdAt = Instant.now();
}
