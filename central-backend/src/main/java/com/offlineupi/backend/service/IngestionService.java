package com.offlineupi.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offlineupi.backend.crypto.HybridCryptoService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;

/**
 * THE central pipeline — called for every packet a bridge/edge node uploads.
 *
 * Pipeline steps:
 *   1. Hash ciphertext (SHA-256)          -> idempotency key
 *   2. Redis SETNX on hash               -> exactly-once gate
 *   3. Decrypt (RSA-OAEP + AES-GCM)      -> get PaymentInstruction
 *   4. Freshness check (signedAt < 24h)  -> replay attack prevention
 *   5. Wallet balance check              -> offline spend limit
 *   6. @Transactional debit + credit     -> settlement
 *   7. Metrics                           -> observability
 */
@Slf4j
@Service
public class IngestionService {

    private final HybridCryptoService crypto;
    private final IdempotencyService  idempotency;
    private final SettlementService   settlement;
    private final WalletService       wallet;
    private final ObjectMapper        mapper;

    private final Counter settledCounter;
    private final Counter duplicateCounter;
    private final Counter invalidCounter;
    private final Timer   settlementTimer;

    @Value("${packet.freshness.window.ms:86400000}")
    private long freshnessWindowMs;

    public IngestionService(
            HybridCryptoService crypto,
            IdempotencyService idempotency,
            SettlementService settlement,
            WalletService wallet,
            MeterRegistry meterRegistry
    ) {
        this.crypto      = crypto;
        this.idempotency = idempotency;
        this.settlement  = settlement;
        this.wallet      = wallet;
        this.mapper      = new ObjectMapper();

        this.settledCounter   = Counter.builder("upi.packets.settled")
            .description("Total packets successfully settled").register(meterRegistry);
        this.duplicateCounter = Counter.builder("upi.packets.duplicate")
            .description("Total duplicate packets dropped").register(meterRegistry);
        this.invalidCounter   = Counter.builder("upi.packets.invalid")
            .description("Total invalid/tampered packets rejected").register(meterRegistry);
        this.settlementTimer  = Timer.builder("upi.settlement.latency")
            .description("Time from packet creation to settlement").register(meterRegistry);
    }

    public record IngestResult(String outcome, String packetHash, String reason, Long transactionId) {}

    public IngestResult ingest(String ciphertextBase64, String edgeNodeId) {
        String packetHash;

        // Step 1: Hash
        try {
            packetHash = crypto.sha256Hex(ciphertextBase64);
        } catch (Exception e) {
            log.error("Failed to hash ciphertext from edgeNode={}: {}", edgeNodeId, e.getMessage());
            invalidCounter.increment();
            return new IngestResult("INVALID", null, "Failed to hash ciphertext", null);
        }

        // Step 2: Idempotency gate (Redis SETNX)
        if (!idempotency.claim(packetHash)) {
            duplicateCounter.increment();
            return new IngestResult("DUPLICATE_DROPPED", packetHash, null, null);
        }

        // Step 3: Decrypt
        Map<String, Object> instruction;
        try {
            String plaintext = crypto.decrypt(ciphertextBase64);
            instruction = mapper.readValue(plaintext, Map.class);
        } catch (Exception e) {
            log.warn("Decryption failed for packetHash={} edgeNode={}: {}", packetHash, edgeNodeId, e.getMessage());
            invalidCounter.increment();
            return new IngestResult("INVALID", packetHash, "Decryption failed - possible tampering", null);
        }

        // Step 4: Freshness check
        try {
            long signedAt = ((Number) instruction.get("signedAt")).longValue();
            long ageMs = Instant.now().toEpochMilli() - signedAt;
            if (ageMs > freshnessWindowMs) {
                log.warn("STALE packet packetHash={} ageMs={}", packetHash, ageMs);
                invalidCounter.increment();
                return new IngestResult("INVALID", packetHash, "Packet expired (older than 24h)", null);
            }
            if (ageMs < 0) {
                log.warn("FUTURE-DATED packet packetHash={} ageMs={}", packetHash, ageMs);
                invalidCounter.increment();
                return new IngestResult("INVALID", packetHash, "Packet timestamp is in the future", null);
            }
        } catch (Exception e) {
            invalidCounter.increment();
            return new IngestResult("INVALID", packetHash, "Missing or invalid signedAt field", null);
        }

        // Step 5: Extract and validate fields
        String senderVpa, receiverVpa;
        long amountPaise;
        Instant createdAtEdge;
        try {
            senderVpa    = (String) instruction.get("senderVpa");
            receiverVpa  = (String) instruction.get("receiverVpa");
            amountPaise  = ((Number) instruction.get("amountPaise")).longValue();
            long signedAt = ((Number) instruction.get("signedAt")).longValue();
            createdAtEdge = Instant.ofEpochMilli(signedAt);

            if (senderVpa == null || receiverVpa == null || amountPaise <= 0) {
                throw new IllegalArgumentException("Missing required fields");
            }
        } catch (Exception e) {
            invalidCounter.increment();
            return new IngestResult("INVALID", packetHash, "Malformed payment instruction: " + e.getMessage(), null);
        }

        // Step 6: Wallet balance check
        if (!wallet.hasSufficientWalletBalance(edgeNodeId, senderVpa, amountPaise)) {
            log.warn("WALLET_EXCEEDED edgeNode={} sender={} amount={}p", edgeNodeId, senderVpa, amountPaise);
            invalidCounter.increment();
            return new IngestResult("INVALID", packetHash, "Offline wallet balance exceeded", null);
        }

        // Step 7: Settle
        long settlementStart = System.currentTimeMillis();
        SettlementService.SettleResult result = settlement.settle(
            senderVpa, receiverVpa, amountPaise, packetHash, edgeNodeId, createdAtEdge
        );
        long settlementMs = System.currentTimeMillis() - settlementStart;

        settlementTimer.record(java.time.Duration.ofMillis(
            Instant.now().toEpochMilli() - createdAtEdge.toEpochMilli()
        ));

        // Step 8: Post-settlement
        if (result == SettlementService.SettleResult.SETTLED) {
            wallet.deductFromWallet(edgeNodeId, senderVpa, amountPaise);
            settledCounter.increment();
            log.info("SETTLED packetHash={} sender={} receiver={} amount={}p edgeNode={} latencyMs={}",
                packetHash, senderVpa, receiverVpa, amountPaise, edgeNodeId, settlementMs);
            return new IngestResult("SETTLED", packetHash, null, null);
        } else if (result == SettlementService.SettleResult.INSUFFICIENT_FUNDS) {
            invalidCounter.increment();
            return new IngestResult("INSUFFICIENT_FUNDS", packetHash, "Sender has insufficient main account balance", null);
        } else {
            invalidCounter.increment();
            return new IngestResult("INVALID", packetHash, "Account not found", null);
        }
    }
}
