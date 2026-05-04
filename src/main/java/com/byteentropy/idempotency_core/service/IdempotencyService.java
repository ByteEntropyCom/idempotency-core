package com.byteentropy.idempotency_core.service;

import com.byteentropy.idempotency_core.model.IdempotencyRecord;
import com.byteentropy.idempotency_core.model.IdempotencyStatus;
import com.byteentropy.idempotency_core.storage.IdempotencyStore;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

@Service
public class IdempotencyService {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyStore store;
    private final ObjectMapper hashMapper;

    @Value("${idempotency.processing-timeout-ms:300000}")
    private long processingTimeoutMs;

    public IdempotencyService(IdempotencyStore store) {
        this.store = store;
        this.hashMapper = store.getObjectMapper().copy()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    public String generateHash(Object payload) {
        if (payload == null) return "null-payload";
        try {
            String json = (payload instanceof Object[] args) 
                ? serializeArgs(args) 
                : hashMapper.writeValueAsString(payload);
                
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedHash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encodedHash);
        } catch (Exception e) {
            log.warn("Hash generation failed, falling back to identity hash", e);
            return "fallback-" + Objects.hash(payload);
        }
    }

    private String serializeArgs(Object[] args) throws Exception {
        StringBuilder sb = new StringBuilder();
        for (Object arg : args) {
            sb.append(arg == null ? "null" : hashMapper.writeValueAsString(arg));
        }
        return sb.toString();
    }

    public IdempotencyRecord attemptReservation(String ns, String key, String hash, long ttl) {
        IdempotencyRecord initial = IdempotencyRecord.builder()
                .status(IdempotencyStatus.PROCESSING)
                .requestHash(hash)
                .timestamp(System.currentTimeMillis())
                .build();

        Object result = store.executeLua(ns, key, initial, ttl);
        return (IdempotencyRecord) result;
    }

    public void commit(String ns, String key, String hash, Object response, long ttl) {
        IdempotencyRecord completed = IdempotencyRecord.builder()
                .status(IdempotencyStatus.COMPLETED)
                .response(response)
                .requestHash(hash)
                .timestamp(System.currentTimeMillis())
                .build();
        store.save(ns, key, completed, ttl);
    }

    public void rollback(String ns, String key) {
        store.delete(ns, key);
    }

    public long getProcessingTimeoutMs() {
        return processingTimeoutMs;
    }
}