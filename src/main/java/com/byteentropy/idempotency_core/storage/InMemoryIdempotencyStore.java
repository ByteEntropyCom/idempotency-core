package com.byteentropy.idempotency_core.storage;

import com.byteentropy.idempotency_core.model.IdempotencyRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.Optional;

@Component
@ConditionalOnProperty(name = "idempotency.storage.type", havingValue = "memory")
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Cache<String, IdempotencyRecord> cache;
    private final ObjectMapper objectMapper;

    public InMemoryIdempotencyStore(Cache<String, IdempotencyRecord> cache, ObjectMapper objectMapper) {
        this.cache = cache;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<IdempotencyRecord> get(String namespace, String key) {
        return Optional.ofNullable(cache.getIfPresent(combine(namespace, key)));
    }

    @Override
    public void save(String namespace, String key, IdempotencyRecord record, long ttl) {
        cache.put(combine(namespace, key), record);
    }

    @Override
    public void delete(String namespace, String key) {
        cache.invalidate(combine(namespace, key));
    }

    @Override
    public Object executeLua(String namespace, String key, IdempotencyRecord initial, long ttl) {
        String fullKey = combine(namespace, key);
        
        // This is the critical fix:
        // putIfAbsent returns the PREVIOUS value associated with the key.
        // If it returns NULL, the 'initial' record was successfully inserted (First execution).
        // If it returns a VALUE, that record already existed (Concurrent execution).
        return cache.asMap().putIfAbsent(fullKey, initial);
    }

    @Override
    public ObjectMapper getObjectMapper() {
        return this.objectMapper;
    }

    private String combine(String namespace, String key) {
        Assert.hasText(namespace, "Namespace required");
        Assert.hasText(key, "Key required");
        return namespace + ":" + key;
    }
}