package com.byteentropy.idempotency_core.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
public class IdempotencyExceptionHandler {

    // Handles the "Payload Mismatch" (Same key, different data)
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Object> handleConflict(IllegalStateException ex) {
        if (ex.getMessage().contains("Idempotency")) {
            return buildResponse(HttpStatus.CONFLICT, ex.getMessage());
        }
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, ex.getMessage());
    }

    // Handles "Duplicate request: Still processing"
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Object> handleProcessing(RuntimeException ex) {
        // Check for "progress" or "processing" (case-insensitive)
        String msg = ex.getMessage().toLowerCase();
        if (msg.contains("progress") || msg.contains("processing")) {
            return buildResponse(HttpStatus.TOO_EARLY, "Request is already being handled. Please wait.");
        }
        return buildResponse(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
    }

    private ResponseEntity<Object> buildResponse(HttpStatus status, String message) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        return new ResponseEntity<>(body, status);
    }
}