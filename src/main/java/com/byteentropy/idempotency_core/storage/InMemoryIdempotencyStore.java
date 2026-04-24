package com.byteentropy.idempotency_core.storage;

import com.byteentropy.idempotency_core.model.IdempotencyRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;


@Component
@ConditionalOnProperty(name = "idempotency.storage.type", havingValue = "memory")
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, IdempotencyRecord> storage = new ConcurrentHashMap<>();

    @Override
    public Optional<IdempotencyRecord> get(String key) {
        return Optional.ofNullable(storage.get(key));
    }

    @Override
    public void save(String key, IdempotencyRecord record, long ttl) {
        storage.put(key, record);
    }

    @Override
    public void delete(String key) {
        storage.remove(key);
    }

    @Override
    public Object executeLua(String key, IdempotencyRecord initial, long ttl) {
        /*
         * putIfAbsent is the Java equivalent of our Redis Lua script.
         * It atomically checks if the key exists. 
         * - If it DOES NOT exist: it puts 'initial' and returns null.
         * - If it DOES exist: it returns the existing record.
         */
        return storage.putIfAbsent(key, initial);
    }
}