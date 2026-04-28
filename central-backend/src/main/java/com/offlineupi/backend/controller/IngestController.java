package com.offlineupi.backend.controller;

import com.offlineupi.backend.service.IngestionService;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The production endpoint that edge nodes POST packets to.
 *
 * Rate limiting: each edge node gets its own token bucket (60 req/min by default).
 * This prevents a buggy or malicious edge node from flooding the backend.
 */
@Slf4j
@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private final IngestionService ingestionService;
    private final int requestsPerMinute;

    // Per-edge-node rate limit buckets
    private final ConcurrentHashMap<String, Bucket> buckets = new ConcurrentHashMap<>();

    public IngestController(
            IngestionService ingestionService,
            @Value("${ratelimit.requests.per.minute:60}") int requestsPerMinute
    ) {
        this.ingestionService  = ingestionService;
        this.requestsPerMinute = requestsPerMinute;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> ingest(
            @RequestHeader(value = "X-Edge-Node-Id", defaultValue = "unknown") String edgeNodeId,
            @RequestBody Map<String, String> body
    ) {
        // ── Rate limiting ──────────────────────────────────────────────────
        Bucket bucket = buckets.computeIfAbsent(edgeNodeId, this::newBucket);
        if (!bucket.tryConsume(1)) {
            log.warn("RATE_LIMITED edgeNode={}", edgeNodeId);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("outcome", "RATE_LIMITED", "reason", "Too many requests"));
        }

        // ── Ingest ─────────────────────────────────────────────────────────
        String ciphertext = body.get("ciphertext");
        if (ciphertext == null || ciphertext.isBlank()) {
            return ResponseEntity.badRequest()
                .body(Map.of("outcome", "INVALID", "reason", "Missing ciphertext field"));
        }

        IngestionService.IngestResult result = ingestionService.ingest(ciphertext, edgeNodeId);

        HttpStatus status = switch (result.outcome()) {
            case "SETTLED"           -> HttpStatus.OK;
            case "DUPLICATE_DROPPED" -> HttpStatus.OK;       // idempotent = success from client POV
            case "INSUFFICIENT_FUNDS"-> HttpStatus.UNPROCESSABLE_ENTITY;
            default                  -> HttpStatus.BAD_REQUEST;
        };

        return ResponseEntity.status(status).body(Map.of(
            "outcome",    result.outcome(),
            "packetHash", result.packetHash() != null ? result.packetHash() : "",
            "reason",     result.reason()     != null ? result.reason()     : ""
        ));
    }

    private Bucket newBucket(String edgeNodeId) {
        Bandwidth limit = Bandwidth.simple(requestsPerMinute, Duration.ofMinutes(1));
        return Bucket.builder().addLimit(limit).build();
    }
}
