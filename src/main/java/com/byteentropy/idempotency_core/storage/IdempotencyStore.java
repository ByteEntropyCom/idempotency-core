package com.byteentropy.idempotency_core.storage;

import com.byteentropy.idempotency_core.model.IdempotencyRecord;
import java.util.Optional;



public interface IdempotencyStore {
    Optional<IdempotencyRecord> get(String key);
    void save(String key, IdempotencyRecord record, long ttl);
    void delete(String key);
    Object executeLua(String key, IdempotencyRecord record, long ttl);
}