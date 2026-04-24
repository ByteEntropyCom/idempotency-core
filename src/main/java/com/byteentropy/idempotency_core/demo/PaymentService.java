package com.byteentropy.idempotency_core.demo;

import com.byteentropy.idempotency_core.annotation.Idempotent;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class PaymentService {
    
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    private int actualExecutionCount = 0;

    @Idempotent(key = "#key", ttl = 60) 
    public String process(String key, int amount, int delayMs) {
        // Simulating work (e.g., calling a bank API)
        if (delayMs > 0) {
            try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        // This block only runs if the Aspect/Lua script doesn't find a cached record
        actualExecutionCount++;
        
        log.info(">>> [SERVICE] Executing REAL logic for key: {}", key);
        log.info(">>> [SERVICE] Total actual executions since startup: {}", actualExecutionCount);
        
        return String.format("Paid successfully! Order: %s | Amount: $%d | TS: %d", 
                key, amount, System.currentTimeMillis());
    }
}