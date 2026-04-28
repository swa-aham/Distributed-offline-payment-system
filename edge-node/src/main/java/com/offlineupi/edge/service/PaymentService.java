package com.offlineupi.edge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.offlineupi.edge.config.EdgeConfig;
import com.offlineupi.edge.crypto.ClientCryptoService;
import com.offlineupi.edge.model.OutboxPacket;
import com.offlineupi.edge.model.OutboxRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Handles payment creation on the edge node.
 *
 * Flow:
 *   1. Check local wallet balance (prevent double-spend offline)
 *   2. Deduct from local wallet (reserve the funds)
 *   3. Build PaymentInstruction JSON with nonce + timestamp
 *   4. Encrypt with server's public key
 *   5. Write to local outbox (PENDING)
 *   6. SyncService will pick it up and deliver when online
 *
 * Note: We do NOT use @RequiredArgsConstructor here because we also
 * need to initialize ObjectMapper as a field — manual constructor is cleaner.
 */
@Slf4j
@Service
public class PaymentService {

    private final OutboxRepository    outboxRepository;
    private final ClientCryptoService crypto;
    private final WalletManager       wallet;
    private final EdgeConfig          config;
    private final ObjectMapper        mapper;

    public PaymentService(OutboxRepository outboxRepository,
                          ClientCryptoService crypto,
                          WalletManager wallet,
                          EdgeConfig config) {
        this.outboxRepository = outboxRepository;
        this.crypto           = crypto;
        this.wallet           = wallet;
        this.config           = config;
        this.mapper           = new ObjectMapper();
    }

    public record PaymentResult(boolean success, String message, Long outboxId) {}

    @Transactional
    public PaymentResult createPayment(String senderVpa, String receiverVpa, long amountPaise) {
        // Step 1: Wallet check
        if (!wallet.deduct(amountPaise)) {
            return new PaymentResult(false,
                "Insufficient offline wallet balance. Current: Rs " + wallet.getBalance() / 100.0 +
                ". Top up the wallet first.", null);
        }

        // Step 2: Build PaymentInstruction
        String nonce    = UUID.randomUUID().toString();
        long signedAt   = Instant.now().toEpochMilli();

        Map<String, Object> instruction = Map.of(
            "senderVpa",   senderVpa,
            "receiverVpa", receiverVpa,
            "amountPaise", amountPaise,
            "nonce",       nonce,
            "signedAt",    signedAt,
            "edgeNodeId",  config.getId()
        );

        // Step 3: Encrypt
        String ciphertext;
        try {
            String json = mapper.writeValueAsString(instruction);
            ciphertext = crypto.encrypt(json);
        } catch (Exception e) {
            wallet.refund(amountPaise);
            log.error("Encryption failed: {}", e.getMessage());
            return new PaymentResult(false, "Encryption failed: " + e.getMessage(), null);
        }

        // Step 4: Write to outbox
        OutboxPacket packet = new OutboxPacket();
        packet.setSenderVpa(senderVpa);
        packet.setReceiverVpa(receiverVpa);
        packet.setAmountPaise(amountPaise);
        packet.setCiphertext(ciphertext);
        packet.setNonce(nonce);
        packet.setCreatedAt(Instant.ofEpochMilli(signedAt));
        OutboxPacket saved = outboxRepository.save(packet);

        log.info("Payment queued: outboxId={} sender={} receiver={} amount={}p nonce={}",
            saved.getId(), senderVpa, receiverVpa, amountPaise, nonce);

        return new PaymentResult(true,
            "Payment queued (outboxId=" + saved.getId() + "). Will sync when online.", saved.getId());
    }
}
