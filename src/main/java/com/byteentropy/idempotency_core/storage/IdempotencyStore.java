package com.byteentropy.idempotency_core.storage;

import java.util.Optional;

import com.byteentropy.idempotency_core.model.IdempotencyRecord;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Interface for idempotency storage. 
 * Supports Namespacing for multi-tenant library usage.
 */
public interface IdempotencyStore {

    Optional<IdempotencyRecord> get(String namespace, String key);

    void save(String namespace, String key, IdempotencyRecord record, long ttl);
    
    void delete(String namespace, String key);

    ObjectMapper getObjectMapper();
    
    Object executeLua(String namespace, String key, IdempotencyRecord record, long ttl);
}