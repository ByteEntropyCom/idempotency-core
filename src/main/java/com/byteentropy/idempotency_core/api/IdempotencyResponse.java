package com.byteentropy.idempotency_core.api;

import com.byteentropy.idempotency_core.model.IdempotencyRecord;
import com.byteentropy.idempotency_core.model.IdempotencyStatus;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record IdempotencyResponse(
    String key,
    IdempotencyStatus status,
    Object data,         // The cached result if status is COMPLETED
    String message,      // Friendly status message
    long timestamp
) {
    public static IdempotencyResponse of(String key, IdempotencyRecord record, String message) {
        return new IdempotencyResponse(
            key, 
            record.getStatus(), 
            record.getResponse(), 
            message, 
            record.getTimestamp()
        );
    }
}