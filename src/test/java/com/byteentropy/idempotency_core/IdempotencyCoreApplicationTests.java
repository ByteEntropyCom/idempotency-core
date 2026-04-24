package com.byteentropy.idempotency_core;

import com.byteentropy.idempotency_core.annotation.Idempotent;
import com.byteentropy.idempotency_core.model.IdempotencyRecord;
import com.byteentropy.idempotency_core.model.IdempotencyStatus;
import com.byteentropy.idempotency_core.storage.IdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
class IdempotencyCoreApplicationTests {

    @Autowired
    private TestService testService;

    @MockitoBean
    private IdempotencyStore idempotencyStore;

    @BeforeEach
    void setup() {
        testService.reset();
    }

    private String calculateHash(Object... args) {
        StringBuilder sb = new StringBuilder();
        for (Object arg : args) sb.append(arg != null ? arg.toString() : "null");
        return DigestUtils.md5DigestAsHex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
void testCondition1_SuccessReplay() {
    String key = "order-123";
    String hash = calculateHash(key);
    String expectedResponse = "Processed " + key; // Match what the service returns
    
    // Setup: Second call is a hit (returns completed record with the SAME response)
    IdempotencyRecord completed = new IdempotencyRecord(
            IdempotencyStatus.COMPLETED, 
            expectedResponse, // Updated this from "Success"
            hash, 
            1000L
    );
    
    Mockito.when(idempotencyStore.executeLua(anyString(), any(), anyLong()))
           .thenReturn(null)      // First call (miss)
           .thenReturn(completed); // Second call (hit)

    String res1 = testService.doWork(key);
    String res2 = testService.doWork(key);

    // Now they will both be "Processed order-123"
    assertEquals(res1, res2);
    assertEquals(expectedResponse, res2);
    assertEquals(1, testService.getInvocationCount(), "Business logic must only run once");
}

    @Test
    void testCondition2_PayloadMismatch() {
        String key = "order-123";
        
        // Setup: Record exists but has a DIFFERENT hash (e.g., different amount)
        IdempotencyRecord existingWithDifferentData = new IdempotencyRecord(
                IdempotencyStatus.COMPLETED, "Success", "different_hash_here", 1000L);

        Mockito.when(idempotencyStore.executeLua(anyString(), any(), anyLong()))
               .thenReturn(existingWithDifferentData);

        // Expect IllegalStateException due to hash mismatch
        Exception exception = assertThrows(IllegalStateException.class, () -> {
            testService.doWork(key);
        });

        assertTrue(exception.getMessage().contains("conflict"));
        assertEquals(0, testService.getInvocationCount(), "Logic should NOT run on conflict");
    }

    @Test
    void testCondition3_ConcurrentRequest_StillProcessing() {
        String key = "order-race";
        String hash = calculateHash(key);
        
        // Setup: Record exists and status is PROCESSING
        IdempotencyRecord processingRecord = new IdempotencyRecord(
                IdempotencyStatus.PROCESSING, null, hash, 1000L);

        Mockito.when(idempotencyStore.executeLua(anyString(), any(), anyLong()))
               .thenReturn(processingRecord);

        // Expect RuntimeException for "Still processing"
        Exception exception = assertThrows(RuntimeException.class, () -> {
            testService.doWork(key);
        });

        assertTrue(exception.getMessage().contains("progress") || exception.getMessage().contains("processing"));
        assertEquals(0, testService.getInvocationCount());
    }

    @Test
    void testCondition4_SelfHealing_OnFailure() {
        String key = "order-fail";
        
        Mockito.when(idempotencyStore.executeLua(anyString(), any(), anyLong())).thenReturn(null);

        // Service throws error
        assertThrows(RuntimeException.class, () -> testService.doWorkAndFail(key));

        // Verify the store.delete(key) was called so the user can retry
        Mockito.verify(idempotencyStore, Mockito.times(1)).delete(eq(key));
    }
}

@Service
class TestService {
    private int invocationCount = 0;

    @Idempotent(key = "#id")
    public String doWork(String id) {
        invocationCount++;
        return "Processed " + id;
    }

    @Idempotent(key = "#id")
    public String doWorkAndFail(String id) {
        throw new RuntimeException("DB Error");
    }

    public void reset() { invocationCount = 0; }
    public int getInvocationCount() { return invocationCount; }
}