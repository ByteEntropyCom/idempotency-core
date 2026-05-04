package com.byteentropy.idempotency_core;

import com.byteentropy.idempotency_core.api.IdempotencyRequest;
import com.byteentropy.idempotency_core.annotation.Idempotent;
import com.byteentropy.idempotency_core.model.IdempotencyRecord;
import com.byteentropy.idempotency_core.model.IdempotencyStatus;
import com.byteentropy.idempotency_core.storage.IdempotencyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
    "idempotency.storage.type=memory",
    "idempotency.default-ttl=60",
    "idempotency.processing-timeout-ms=2000" // Reduced for faster recovery testing
})
@AutoConfigureMockMvc
class IdempotencyCoreApplicationTests {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private TestService testService;
    @Autowired private IdempotencyStore store;

    @BeforeEach
    void setup() {
        testService.resetCounter();
    }

    // --- EXISTING CORE TESTS ---

    @Test
    void testRestApiReservation() throws Exception {
        String key = "api-key";
        IdempotencyRequest request = new IdempotencyRequest(key, "default", Map.of("cmd", "run"), 60);

        mockMvc.perform(post("/api/v1/idempotency/check")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
        
        assertTrue(store.get("default", key).isPresent());
    }

    @Test
    void testConcurrency() throws InterruptedException {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < threads; i++) {
            executor.execute(() -> {
                try {
                    latch.await();
                    testService.execute("concurrency-key");
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        assertEquals(1, successCount.get());
        assertEquals(9, errorCount.get());
    }

    // --- PENDING "SAD PATH" TESTS ---

    /**
     * Case 1: Data Integrity (Payload Mismatch)
     * Verifies that the same key with DIFFERENT data is rejected.
     */
    @Test
    void testPayloadMismatchRejection() {
        String key = "integrity-key";
        
        // First call with Payload A
        testService.executeWithPayload(key, "Payload-A");
        
        // Second call with Payload B should trigger IllegalStateException (Conflict)
        assertThrows(IllegalStateException.class, () -> 
            testService.executeWithPayload(key, "Payload-B")
        );
    }

    /**
     * Case 2: Resilience (Method Failure)
     * Verifies that if the business logic crashes, the lock is released immediately.
     */
    @Test
    void testLockReleaseOnMethodFailure() {
        String key = "failure-key";
        
        // Trigger a method that fails
        assertThrows(RuntimeException.class, () -> testService.executeAndFail(key));
        
        // The lock should be GONE. A second call should succeed.
        assertDoesNotThrow(() -> testService.execute(key));
        assertEquals(1, testService.getExecutionCount());
    }

    /**
     * Case 3: Auto-Healing (Ghost Lock Recovery)
     * Verifies that if a server died without releasing a lock, it heals after timeout.
     */
    @Test
    void testGhostLockRecovery() throws InterruptedException {
        String key = "ghost-key";
        String ns = "test-service";

        // Manually inject a "stuck" processing record into storage
        IdempotencyRecord stuckRecord = IdempotencyRecord.builder()
                .status(IdempotencyStatus.PROCESSING)
                .requestHash("some-hash")
                .timestamp(System.currentTimeMillis())
                .build();
        store.save(ns, key, stuckRecord, 60);

        // Immediate call should fail (it's still "processing")
        assertThrows(RuntimeException.class, () -> testService.execute(key));

        // Wait for processing-timeout-ms (set to 2000ms in properties)
        Thread.sleep(2100);

        // Next call should detect the "Ghost Lock", clear it, and succeed
        assertDoesNotThrow(() -> testService.execute(key));
        assertEquals(1, testService.getExecutionCount());
    }

    // --- TEST SUPPORT SERVICE ---

    @Service
    public static class TestService {
        private final AtomicInteger executionCount = new AtomicInteger(0);
        public void resetCounter() { executionCount.set(0); }
        public int getExecutionCount() { return executionCount.get(); }

        @Idempotent(key = "#id", namespace = "test-service")
        public String execute(String id) {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
            executionCount.incrementAndGet();
            return "Done";
        }

        @Idempotent(key = "#id", namespace = "test-service")
        public String executeWithPayload(String id, String data) {
            return "DataProcessed";
        }

        @Idempotent(key = "#id", namespace = "test-service")
        public String executeAndFail(String id) {
            throw new RuntimeException("Simulated Business Logic Failure");
        }
    }

    @Test
    void testCachedResultTypeSafety() {
        String key = "type-safety-key";
        
        // First call executes logic and returns a String
        String firstResult = testService.execute(key);
        
        // Second call should return the EXACT SAME string from cache
        Object cachedResult = testService.execute(key);
        
        assertEquals(firstResult, cachedResult);
        assertInstanceOf(String.class, cachedResult);
    }
}