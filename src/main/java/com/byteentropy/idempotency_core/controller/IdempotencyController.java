package com.byteentropy.idempotency_core.controller;

import com.byteentropy.idempotency_core.model.*;
import com.byteentropy.idempotency_core.api.IdempotencyRequest;
import com.byteentropy.idempotency_core.api.IdempotencyResponse;
import com.byteentropy.idempotency_core.storage.IdempotencyStore;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/idempotency")
public class IdempotencyController {

    private final IdempotencyStore store;
    private final ObjectMapper hashMapper;

    public IdempotencyController(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        // Use a consistent, safe mapper for hashing
        this.hashMapper = objectMapper.copy()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @PostMapping("/check")
    public ResponseEntity<IdempotencyResponse> check(@RequestBody IdempotencyRequest request) throws Exception {
        
        if (!StringUtils.hasText(request.namespace())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new IdempotencyResponse(request.key(), null, null, "Namespace is required", System.currentTimeMillis()));
        }
        
        String ns = request.namespace();
        String currentHash = generateHash(request.payload());

        IdempotencyRecord initial = IdempotencyRecord.builder()
                .status(IdempotencyStatus.PROCESSING)
                .requestHash(currentHash)
                .timestamp(System.currentTimeMillis())
                .build();

        Object result = store.executeLua(ns, request.key(), initial, request.ttl());

        if (result != null) {
            IdempotencyRecord existing = (IdempotencyRecord) result;
            
            // Critical: Must compare SHA-256 hashes
            if (!Objects.equals(existing.getRequestHash(), currentHash)) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(new IdempotencyResponse(request.key(), null, null, "Payload mismatch", System.currentTimeMillis()));
            }
            
            if (existing.getStatus() == IdempotencyStatus.COMPLETED) {
                return ResponseEntity.ok(IdempotencyResponse.of(request.key(), existing, "Success (Cached)"));
            }
            
            return ResponseEntity.status(HttpStatus.TOO_EARLY)
                .body(IdempotencyResponse.of(request.key(), existing, "Processing in progress"));
        }

        return ResponseEntity.status(HttpStatus.CREATED)
            .body(new IdempotencyResponse(request.key(), IdempotencyStatus.PROCESSING, null, "Reserved", System.currentTimeMillis()));
    }

    @PostMapping("/complete")
    public ResponseEntity<IdempotencyResponse> complete(@RequestBody CompletionRequest wrapper) throws Exception {
        IdempotencyRequest request = wrapper.request();
        
        if (!StringUtils.hasText(request.namespace())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        String ns = request.namespace();
        String hash = generateHash(request.payload());

        IdempotencyRecord completed = IdempotencyRecord.builder()
                .status(IdempotencyStatus.COMPLETED)
                .response(wrapper.resultData())
                .requestHash(hash)
                .timestamp(System.currentTimeMillis())
                .build();

        store.save(ns, request.key(), completed, request.ttl());
        return ResponseEntity.ok().build();
    }

    /**
     * Internal helper to generate SHA-256 hash consistent with IdempotencyAspect
     */
    private String generateHash(Object payload) throws Exception {
        if (payload == null) return "null-payload";
        
        String json = hashMapper.writeValueAsString(payload);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] encodedHash = digest.digest(json.getBytes(StandardCharsets.UTF_8));
        
        return HexFormat.of().formatHex(encodedHash);
    }

    public record CompletionRequest(IdempotencyRequest request, Object resultData) {}
}