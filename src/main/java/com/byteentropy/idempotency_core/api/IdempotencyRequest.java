package com.byteentropy.idempotency_core.api;

import java.util.Map;

/**
 * The incoming request from a client service (Python, Go, etc.)
 */
public record IdempotencyRequest(
    String key,
    String namespace,
    Map<String, Object> payload, // Used for hashing to ensure data integrity
    long ttl                     // Optional override for TTL
) {}