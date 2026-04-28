package com.offlineupi.edge.service;

import com.offlineupi.edge.config.EdgeConfig;
import com.offlineupi.edge.model.OutboxPacket;
import com.offlineupi.edge.model.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Background scheduler that flushes the outbox to the central backend.
 *
 * Key behaviors:
 * - Runs every N seconds (configurable)
 * - Skips if offline.mode=true (simulates no internet)
 * - Exponential backoff on failure (1s → 2s → 4s → 8s → 16s)
 * - Marks packets DELIVERED on success, FAILED after max attempts
 * - DUPLICATE_DROPPED from backend = also treated as success (idempotent)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SyncService {

    private final OutboxRepository outboxRepository;
    private final WalletManager    wallet;
    private final EdgeConfig       config;
    private final RestTemplate     restTemplate = new RestTemplate();

    @Value("${backend.url:http://localhost:8080}")
    private String backendUrl;

    @Value("${offline.mode:false}")
    private boolean offlineMode;

    @Value("${retry.max.attempts:5}")
    private int maxAttempts;

    @Value("${retry.initial.backoff.ms:1000}")
    private long initialBackoffMs;

    @Value("${retry.backoff.multiplier:2}")
    private long backoffMultiplier;

    /**
     * Scheduled outbox flush.
     * fixedDelayString ensures the next run starts N seconds after the LAST run finished,
     * not N seconds after it started — preventing overlapping runs.
     */
    @Scheduled(fixedDelayString = "${sync.interval.seconds:10}000")
    public void syncOutbox() {
        if (offlineMode) {
            log.debug("[OFFLINE MODE] Skipping sync.");
            return;
        }

        List<OutboxPacket> pending = outboxRepository
            .findByStatusAndNextRetryAtBefore("PENDING", Instant.now());

        if (pending.isEmpty()) return;

        log.info("Syncing {} pending packet(s) to backend...", pending.size());

        for (OutboxPacket packet : pending) {
            deliver(packet);
        }
    }

    private void deliver(OutboxPacket packet) {
        packet.setAttemptCount(packet.getAttemptCount() + 1);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("X-Edge-Node-Id", config.getId());

            Map<String, String> body = Map.of("ciphertext", packet.getCiphertext());
            HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(
                backendUrl + "/api/ingest", request, Map.class
            );

            String outcome = (String) response.getBody().get("outcome");
            packet.setBackendOutcome(outcome);

            if ("SETTLED".equals(outcome) || "DUPLICATE_DROPPED".equals(outcome)) {
                packet.setStatus("DELIVERED");
                log.info("DELIVERED outboxId={} outcome={} sender={} receiver={} amount={}p",
                    packet.getId(), outcome, packet.getSenderVpa(),
                    packet.getReceiverVpa(), packet.getAmountPaise());
            } else {
                // Backend rejected (INVALID, INSUFFICIENT_FUNDS) — no point retrying
                packet.setStatus("FAILED");
                // Refund wallet if backend explicitly rejected
                if ("INSUFFICIENT_FUNDS".equals(outcome) || "INVALID".equals(outcome)) {
                    wallet.refund(packet.getAmountPaise());
                }
                log.warn("REJECTED by backend outboxId={} outcome={} reason={}",
                    packet.getId(), outcome, response.getBody().get("reason"));
            }

        } catch (ResourceAccessException e) {
            // Network error — backend unreachable — schedule retry with backoff
            handleRetryableFailure(packet, "Backend unreachable: " + e.getMessage());

        } catch (HttpClientErrorException e) {
            if (e.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                handleRetryableFailure(packet, "Rate limited by backend");
            } else {
                packet.setStatus("FAILED");
                log.error("Non-retryable HTTP error outboxId={}: {}", packet.getId(), e.getMessage());
            }
        } catch (Exception e) {
            handleRetryableFailure(packet, "Unexpected error: " + e.getMessage());
        }

        outboxRepository.save(packet);
    }

    private void handleRetryableFailure(OutboxPacket packet, String reason) {
        if (packet.getAttemptCount() >= maxAttempts) {
            packet.setStatus("FAILED");
            log.error("MAX ATTEMPTS REACHED outboxId={} — giving up. Reason: {}", packet.getId(), reason);
        } else {
            // Exponential backoff: 1s, 2s, 4s, 8s, 16s
            long backoffMs = initialBackoffMs * (long) Math.pow(backoffMultiplier, packet.getAttemptCount() - 1);
            packet.setNextRetryAt(Instant.now().plusMillis(backoffMs));
            log.warn("RETRY SCHEDULED outboxId={} attempt={}/{} nextRetryIn={}ms reason={}",
                packet.getId(), packet.getAttemptCount(), maxAttempts, backoffMs, reason);
        }
    }

    /** Manually toggle offline mode — called from the REST controller */
    public void setOfflineMode(boolean offline) {
        this.offlineMode = offline;
        log.info("Offline mode: {}", offline);
    }

    public boolean isOfflineMode() {
        return offlineMode;
    }
}
