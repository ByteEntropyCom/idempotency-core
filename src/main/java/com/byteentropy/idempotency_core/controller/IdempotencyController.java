package com.byteentropy.idempotency_core.controller;

import com.byteentropy.idempotency_core.api.IdempotencyRequest;
import com.byteentropy.idempotency_core.api.IdempotencyResponse;
import com.byteentropy.idempotency_core.model.IdempotencyRecord;
import com.byteentropy.idempotency_core.model.IdempotencyStatus;
import com.byteentropy.idempotency_core.service.IdempotencyService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;

@RestController
@RequestMapping("/api/v1/idempotency")
public class IdempotencyController {

    private final IdempotencyService idempotencyService;

    public IdempotencyController(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/check")
    public ResponseEntity<IdempotencyResponse> check(@RequestBody IdempotencyRequest request) {
        if (!StringUtils.hasText(request.namespace())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new IdempotencyResponse(request.key(), null, null, "Namespace is required", System.currentTimeMillis()));
        }
        
        String currentHash = idempotencyService.generateHash(request.payload());
        IdempotencyRecord existing = idempotencyService.attemptReservation(
                request.namespace(), request.key(), currentHash, request.ttl());

        if (existing != null) {
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
    public ResponseEntity<Void> complete(@RequestBody CompletionRequest wrapper) {
        IdempotencyRequest request = wrapper.request();
        if (request == null || !StringUtils.hasText(request.namespace())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        String hash = idempotencyService.generateHash(request.payload());
        idempotencyService.commit(request.namespace(), request.key(), hash, wrapper.resultData(), request.ttl());
        
        return ResponseEntity.ok().build();
    }

    public record CompletionRequest(IdempotencyRequest request, Object resultData) {}
}