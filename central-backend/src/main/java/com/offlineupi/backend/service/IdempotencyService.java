package com.offlineupi.backend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * Guarantees exactly-once processing using Redis SET NX EX.
 *
 * Redis SETNX (Set if Not eXists) is atomic — even across multiple
 * backend instances. This is the distributed equivalent of
 * ConcurrentHashMap.putIfAbsent(), but works across a cluster.
 *
 * Key format: "idempotency:<packetHash>"
 * TTL: 24 hours (matches freshness window — a replay after 24h is
 *      blocked by the timestamp check anyway, so no need to keep longer)
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IdempotencyService {

    private static final String KEY_PREFIX = "idempotency:";

    private final StringRedisTemplate redis;

    @Value("${idempotency.ttl.seconds:86400}")
    private long ttlSeconds;

    /**
     * Attempts to claim this packetHash.
     *
     * @return true if this is the FIRST time we've seen this hash (proceed to settle)
     *         false if it's a duplicate (drop immediately)
     */
    public boolean claim(String packetHash) {
        String key = KEY_PREFIX + packetHash;
        // Boolean.TRUE means the key was set (first claim)
        // null / FALSE means the key already existed (duplicate)
        Boolean wasSet = redis.opsForValue()
            .setIfAbsent(key, "1", Duration.ofSeconds(ttlSeconds));
        boolean isFirst = Boolean.TRUE.equals(wasSet);
        if (!isFirst) {
            log.info("DUPLICATE_DROPPED packetHash={}", packetHash);
        }
        return isFirst;
    }

    /**
     * Check without claiming — used in tests and admin APIs.
     */
    public boolean isSeen(String packetHash) {
        return redis.hasKey(KEY_PREFIX + packetHash);
    }
}
