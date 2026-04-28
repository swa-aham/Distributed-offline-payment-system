package com.offlineupi.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "transactions")
@Getter @Setter @NoArgsConstructor
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * SHA-256 of the ciphertext — unique constraint in DB.
     * This is the database-level idempotency guard (belt-and-suspenders
     * behind the Redis layer).
     */
    @Column(name = "packet_hash", nullable = false, unique = true, length = 64)
    private String packetHash;

    @Column(name = "sender_vpa", nullable = false)
    private String senderVpa;

    @Column(name = "receiver_vpa", nullable = false)
    private String receiverVpa;

    @Column(name = "amount_paise", nullable = false)
    private Long amountPaise;

    @Column(name = "edge_node_id")
    private String edgeNodeId;

    @Column(nullable = false)
    private String outcome; // SETTLED, DUPLICATE_DROPPED, INVALID, INSUFFICIENT_FUNDS

    @Column(name = "settled_at")
    private Instant settledAt = Instant.now();

    @Column(name = "created_at_edge")
    private Instant createdAtEdge; // when the edge node created the packet
}
