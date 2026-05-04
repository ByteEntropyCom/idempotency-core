package com.byteentropy.idempotency_core.controller;

import com.byteentropy.idempotency_core.model.*;
import com.byteentropy.idempotency_core.api.IdempotencyRequest;
import com.byteentropy.idempotency_core.api.IdempotencyResponse;
import com.byteentropy.idempotency_core.storage.IdempotencyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

@RestController
@RequestMapping("/api/v1/idempotency")
public class IdempotencyController {

    private final IdempotencyStore store;
    private final ObjectMapper objectMapper;

    public IdempotencyController(IdempotencyStore store, ObjectMapper objectMapper) {
        this.store = store;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/check")
    public ResponseEntity<IdempotencyResponse> check(@RequestBody IdempotencyRequest request) throws Exception {
        
        // STRICT VALIDATION: Reject request if namespace is missing
        if (!StringUtils.hasText(request.namespace())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new IdempotencyResponse(request.key(), null, null, "Namespace is required", System.currentTimeMillis()));
        }
        
        String ns = request.namespace();
        String payloadJson = objectMapper.writeValueAsString(request.payload());
        String currentHash = DigestUtils.md5DigestAsHex(payloadJson.getBytes(StandardCharsets.UTF_8));

        IdempotencyRecord initial = IdempotencyRecord.builder()
                .status(IdempotencyStatus.PROCESSING)
                .requestHash(currentHash)
                .timestamp(System.currentTimeMillis())
                .build();

        Object result = store.executeLua(ns, request.key(), initial, request.ttl());

        if (result != null) {
            IdempotencyRecord existing = (IdempotencyRecord) result;
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
        String payloadJson = objectMapper.writeValueAsString(request.payload());
        String hash = DigestUtils.md5DigestAsHex(payloadJson.getBytes(StandardCharsets.UTF_8));

        IdempotencyRecord completed = IdempotencyRecord.builder()
                .status(IdempotencyStatus.COMPLETED)
                .response(wrapper.resultData())
                .requestHash(hash)
                .timestamp(System.currentTimeMillis())
                .build();

        store.save(ns, request.key(), completed, request.ttl());
        return ResponseEntity.ok().build();
    }

    public record CompletionRequest(IdempotencyRequest request, Object resultData) {}
}