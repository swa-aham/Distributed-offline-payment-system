package com.offlineupi.edge.controller;

import com.offlineupi.edge.config.EdgeConfig;
import com.offlineupi.edge.model.OutboxRepository;
import com.offlineupi.edge.service.PaymentService;
import com.offlineupi.edge.service.SyncService;
import com.offlineupi.edge.service.WalletManager;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService   paymentService;
    private final SyncService      syncService;
    private final WalletManager    wallet;
    private final OutboxRepository outboxRepository;
    private final EdgeConfig       config;

    /**
     * Create a new payment.
     * POST /api/pay
     * { "senderVpa": "alice@upi", "receiverVpa": "bob@upi", "amountPaise": 50000 }
     */
    @PostMapping("/pay")
    public ResponseEntity<Map<String, Object>> pay(@RequestBody Map<String, Object> body) {
        String senderVpa   = (String) body.get("senderVpa");
        String receiverVpa = (String) body.get("receiverVpa");
        long   amountPaise = ((Number) body.get("amountPaise")).longValue();

        if (senderVpa == null || receiverVpa == null || amountPaise <= 0) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "message", "senderVpa, receiverVpa, and amountPaise (>0) are required"
            ));
        }

        PaymentService.PaymentResult result = paymentService.createPayment(senderVpa, receiverVpa, amountPaise);
        return ResponseEntity
            .status(result.success() ? 200 : 400)
            .body(Map.of(
                "success",  result.success(),
                "message",  result.message(),
                "outboxId", result.outboxId() != null ? result.outboxId() : ""
            ));
    }

    /** Get the current state of this edge node */
    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
            "edgeNodeId",        config.getId(),
            "ownerVpa",          config.getOwnerVpa(),
            "offlineMode",       syncService.isOfflineMode(),
            "walletBalancePaise", wallet.getBalance(),
            "walletBalanceRupees", wallet.getBalance() / 100.0,
            "pendingPackets",    outboxRepository.countByStatus("PENDING"),
            "deliveredPackets",  outboxRepository.countByStatus("DELIVERED"),
            "failedPackets",     outboxRepository.countByStatus("FAILED")
        );
    }

    /** Toggle offline mode — simulates losing/gaining internet */
    @PostMapping("/offline")
    public Map<String, Object> setOffline(@RequestBody Map<String, Boolean> body) {
        boolean offline = Boolean.TRUE.equals(body.get("offline"));
        syncService.setOfflineMode(offline);
        return Map.of(
            "offlineMode", offline,
            "message", offline
                ? "Node is now OFFLINE — payments will queue locally"
                : "Node is now ONLINE — queued payments will sync shortly"
        );
    }

    /** Manually trigger a sync (instead of waiting for the scheduler) */
    @PostMapping("/sync")
    public Map<String, Object> triggerSync() {
        if (syncService.isOfflineMode()) {
            return Map.of("message", "Node is offline — sync skipped");
        }
        syncService.syncOutbox();
        return Map.of("message", "Sync triggered");
    }

    /** Set local wallet balance (after topping up from the backend) */
    @PostMapping("/wallet/set")
    public Map<String, Object> setWallet(@RequestBody Map<String, Object> body) {
        long amountPaise = ((Number) body.get("amountPaise")).longValue();
        wallet.setBalance(amountPaise);
        return Map.of(
            "message",       "Wallet set to " + amountPaise + " paise",
            "balanceRupees", amountPaise / 100.0
        );
    }

    /** View the outbox queue */
    @GetMapping("/outbox")
    public Object outbox() {
        return outboxRepository.findTop20ByOrderByCreatedAtDesc().stream().map(p -> Map.of(
            "id",            p.getId(),
            "senderVpa",     p.getSenderVpa(),
            "receiverVpa",   p.getReceiverVpa(),
            "amountRupees",  p.getAmountPaise() / 100.0,
            "status",        p.getStatus(),
            "attemptCount",  p.getAttemptCount(),
            "createdAt",     p.getCreatedAt().toString(),
            "backendOutcome", p.getBackendOutcome() != null ? p.getBackendOutcome() : ""
        )).toList();
    }
}
