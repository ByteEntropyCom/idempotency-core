# Distributed Idempotency Core

A production-grade, Spring Boot-based idempotency engine that ensures **Exactly-Once** execution for critical business logic (like payments) using Redis, Lua scripting, and Spring AOP.

## 🚀 Key Features

- **Distributed Atomic Locking:** Uses Lua scripts to ensure only one thread across a cluster can process a specific key.
- **Data Integrity (Payload Hashing):** MD5 hashing of request arguments prevents "Key Hijacking" (using the same key for different data).
- **Concurrency Guard:** Automatically rejects overlapping requests for the same key with a `425 Too Early` status.
- **Hybrid Storage:** Supports **Redis** for production and **In-Memory** (ConcurrentHashMap) for local development.
- **Smart TTL Management:** Global default TTL via properties file, with granular method-level overrides.
- **Self-Healing:** Automatically releases locks/keys if the business logic throws an exception, allowing for immediate retries.

---

## 🛠️ Architecture & Flow

The system acts as a "State Machine" for your API requests:

1. **New Request:** Key is reserved as `PROCESSING`.
2. **Concurrent Request:** If status is `PROCESSING`, return `425 Too Early`.
3. **Replay Request:** If status is `COMPLETED`, return cached response + `200 OK`.
4. **Data Mismatch:** If payload hash doesn't match the key, return `409 Conflict`.

---

## 📦 Installation & Configuration

### 1. Application Properties
Add these to your `application.properties`:

```properties
# Storage Mode: 'redis' for cluster, 'memory' for local dev
idempotency.storage.type=redis

# Global Default TTL (in seconds) - Default is 1 hour
idempotency.default-ttl=3600

# Redis Configuration
spring.data.redis.host=your-redis-server
spring.data.redis.port=6379
spring.data.redis.password=your-password

### 2. Usage

Annotate your service methods. Use SpEL (Spring Expression Language) to define the key based on method arguments.

Java
@Service
public class PaymentService {

    // Uses global TTL from properties
    @Idempotent(key = "#orderId")
    public String processPayment(String orderId, double amount) {
        return "Payment of $" + amount + " successful for " + orderId;
    }

    // Overrides global TTL to 10 minutes (600s)
    @Idempotent(key = "#request.id", ttl = 600)
    public Response handleOrder(OrderRequest request) {
        return new Response("Order Processed");
    }
}
🧪 Testing Scenarios (Verified)
Test 1: Success Replay (200 OK)

Action: Send same request twice.
Result: Second request returns the exact same response with the original timestamp, without executing logic again.

Test 2: Concurrent Lock (425 Too Early)

Action: Send Request B while Request A is still sleeping (processing).
Result: Request B receives {"status": 425, "message": "Request is already in progress."}.

Test 3: Payload Mismatch (409 Conflict)

Action: Use Key-A for a $10 payment, then use Key-A again for a $500 payment.
Result: System detects the data change and returns 409 Conflict to prevent fraud/errors.

🚦 HTTP Status Code Mapping
Status	Meaning	Action for Client
200 OK	Success / Cached Result	Proceed normally.
425 Too Early	Request in progress	Show spinner, retry in 2-5 seconds.
409 Conflict	Key/Data mismatch	Critical error: Do not reuse this key for this data.
500 Error	System Failure	Lock is released; safe to retry immediately.
📂 Project Components
@Idempotent: Custom annotation for marking methods.

IdempotencyAspect: The core logic handler (hashing, SpEL, flow control).

IdempotencyStore: Interface for storage abstraction.

RedisIdempotencyStore: Distributed implementation using atomic Lua scripts.

InMemoryIdempotencyStore: Local implementation using ConcurrentHashMap.putIfAbsent().

IdempotencyExceptionHandler: REST Advice to format error responses.


### Final Note
This module is now **fully verified** via both manual `curl` testing and automated JUnit 5 tests. It is ready for integration into a high-concurrency microservices environment.
