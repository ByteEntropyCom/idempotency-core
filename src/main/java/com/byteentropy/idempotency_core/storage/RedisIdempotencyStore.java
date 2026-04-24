package com.byteentropy.idempotency_core.storage;

import com.byteentropy.idempotency_core.model.IdempotencyRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.stereotype.Repository;

import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Repository
@ConditionalOnProperty(name = "idempotency.storage.type", havingValue = "redis", matchIfMissing = true)
public class RedisIdempotencyStore implements IdempotencyStore {

    private final RedisTemplate<String, Object> redisTemplate;
    private final DefaultRedisScript<Object> idempotencyScript;
    private final ObjectMapper objectMapper;

    public RedisIdempotencyStore(RedisTemplate<String, Object> redisTemplate, 
                                 DefaultRedisScript<Object> idempotencyScript,
                                 ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplate;
        this.idempotencyScript = idempotencyScript;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<IdempotencyRecord> get(String key) {
        Object val = redisTemplate.opsForValue().get(genKey(key));
        if (val == null) return Optional.empty();
        return Optional.of(objectMapper.convertValue(val, IdempotencyRecord.class));
    }

    @Override
    public void save(String key, IdempotencyRecord record, long ttl) {
        redisTemplate.opsForValue().set(genKey(key), record, ttl, TimeUnit.SECONDS);
    }

    @Override
    public void delete(String key) {
        redisTemplate.delete(genKey(key));
    }

    @Override
    @SuppressWarnings("unchecked")
    public Object executeLua(String key, IdempotencyRecord record, long ttl) {
        try {
            // Serialize to JSON string for ARGV[1]
            String jsonRecord = objectMapper.writeValueAsString(record);

            // To avoid the "Raw Type" warning and the "Incompatible Types" error:
            // We use the String serializer but cast it to RedisSerializer<Object>
            // using a typed intermediate to keep the compiler happy.
            RedisSerializer<String> stringSerializer = RedisSerializer.string();
            RedisSerializer<Object> resultSerializer = (RedisSerializer<Object>) (RedisSerializer<?>) stringSerializer;

            Object result = redisTemplate.execute(
                idempotencyScript,
                redisTemplate.getStringSerializer(), // argsSerializer
                resultSerializer,                    // resultSerializer (now matches Script<Object>)
                Collections.singletonList(genKey(key)),
                jsonRecord,
                String.valueOf(ttl)
            );

            if (result == null) return null;

            // Result from Lua is the stringified JSON; convert back to POJO
            return objectMapper.readValue(result.toString(), IdempotencyRecord.class);
        } catch (Exception e) {
            throw new RuntimeException("Idempotency storage failure during Lua execution", e);
        }
    }

    private String genKey(String key) {
        return "idemp:" + key;
    }
}