package com.byteentropy.idempotency_core.demo;

import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;

@RestController
@RequestMapping("/api/v1")
public class PaymentController {
    
    @Autowired 
    private PaymentService service;

    @PostMapping("/pay")
    public ResponseEntity<String> pay(
            @RequestHeader("X-Idempotency-Key") String key,
            @RequestParam(defaultValue = "0") int amount,
            @RequestParam(defaultValue = "0") int delayMs) {
        
        // Passing amount to test Hash Mismatch
        // Passing delayMs to test Concurrency (Race Conditions)
        return ResponseEntity.ok(service.process(key, amount, delayMs));
    }
}