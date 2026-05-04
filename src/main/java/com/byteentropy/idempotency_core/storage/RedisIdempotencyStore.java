package com.byteentropy.idempotency_core.storage;

import com.byteentropy.idempotency_core.model.IdempotencyRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Repository;
import org.springframework.util.Assert;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Repository
@ConditionalOnProperty(name = "idempotency.storage.type", havingValue = "redis", matchIfMissing = true)
public class RedisIdempotencyStore implements IdempotencyStore {

    private final StringRedisTemplate redisTemplate; // Switched to String Template for safety
    private final DefaultRedisScript<String> idempotencyScript;
    private final ObjectMapper objectMapper;

    public RedisIdempotencyStore(StringRedisTemplate redisTemplate, 
                                 DefaultRedisScript<String> idempotencyScript,
                                 ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.idempotencyScript = idempotencyScript;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<IdempotencyRecord> get(String namespace, String key) {
        String val = redisTemplate.opsForValue().get(genKey(namespace, key));
        if (val == null) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(val, IdempotencyRecord.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public void save(String namespace, String key, IdempotencyRecord record, long ttl) {
        try {
            String json = objectMapper.writeValueAsString(record);
            redisTemplate.opsForValue().set(genKey(namespace, key), json, ttl, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException("Serialization failed", e);
        }
    }

    @Override
    public void delete(String namespace, String key) {
        redisTemplate.delete(genKey(namespace, key));
    }

    @Override
    public Object executeLua(String namespace, String key, IdempotencyRecord record, long ttl) {
        try {
            String jsonRecord = objectMapper.writeValueAsString(record);
            
            String result = redisTemplate.execute(
                idempotencyScript,
                Collections.singletonList(genKey(namespace, key)),
                jsonRecord,
                String.valueOf(ttl)
            );

            if (result == null) return null;
            return objectMapper.readValue(result, IdempotencyRecord.class);
        } catch (Exception e) {
            throw new RuntimeException("Idempotency storage failure during Lua execution", e);
        }
    }

    @Override
    public ObjectMapper getObjectMapper() {
        return this.objectMapper;
    }

    private String genKey(String namespace, String key) {
        Assert.hasText(namespace, "Namespace required");
        Assert.hasText(key, "Key required");
        return String.format("idemp:%s:%s", namespace, key);
    }
}