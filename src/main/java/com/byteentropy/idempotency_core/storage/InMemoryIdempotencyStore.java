package com.byteentropy.idempotency_core.storage;

import com.byteentropy.idempotency_core.model.IdempotencyRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
@ConditionalOnProperty(name = "idempotency.storage.type", havingValue = "memory")
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, IdempotencyRecord> storage = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    public InMemoryIdempotencyStore(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<IdempotencyRecord> get(String namespace, String key) {
        return Optional.ofNullable(storage.get(combine(namespace, key)));
    }

    @Override
    public void save(String namespace, String key, IdempotencyRecord record, long ttl) {
        storage.put(combine(namespace, key), record);
    }

    @Override
    public void delete(String namespace, String key) {
        storage.remove(combine(namespace, key));
    }

    @Override
    public Object executeLua(String namespace, String key, IdempotencyRecord initial, long ttl) {
        // putIfAbsent returns the previous value, or null if there was no mapping
        // This mimics the Lua script behavior (returning existing record if it exists)
        return storage.putIfAbsent(combine(namespace, key), initial);
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