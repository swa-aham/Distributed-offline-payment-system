package com.offlineupi.edge.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * The Outbox Pattern:
 *
 * Instead of directly calling the backend (which may be unreachable offline),
 * we write to a local outbox table first. A background scheduler reads from
 * this table and tries to deliver to the backend. On success → mark DELIVERED.
 * On failure → increment attempt count, back off, retry later.
 *
 * This guarantees at-least-once delivery. The backend's idempotency layer
 * ensures exactly-once settlement even if we deliver more than once.
 */
@Entity
@Table(name = "outbox_packets")
@Getter @Setter @NoArgsConstructor
public class OutboxPacket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String senderVpa;

    @Column(nullable = false)
    private String receiverVpa;

    @Column(nullable = false)
    private Long amountPaise;

    /** The encrypted payload — this is what gets sent to the backend */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String ciphertext;

    /** Unique nonce — ensures even two identical payments have different ciphertexts */
    @Column(nullable = false)
    private String nonce;

    /** When this payment was created on the edge node */
    @Column(nullable = false)
    private Instant createdAt = Instant.now();

    /** Current delivery status */
    @Column(nullable = false)
    private String status = "PENDING"; // PENDING, DELIVERED, FAILED

    /** How many delivery attempts have been made */
    @Column(nullable = false)
    private int attemptCount = 0;

    /** When to try next (for exponential backoff) */
    @Column
    private Instant nextRetryAt = Instant.now();

    /** The backend's response on successful delivery */
    @Column
    private String backendOutcome;
}
