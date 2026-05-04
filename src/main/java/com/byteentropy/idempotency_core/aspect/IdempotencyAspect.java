package com.byteentropy.idempotency_core.aspect;

import com.byteentropy.idempotency_core.annotation.Idempotent;
import com.byteentropy.idempotency_core.model.IdempotencyRecord;
import com.byteentropy.idempotency_core.model.IdempotencyStatus;
import com.byteentropy.idempotency_core.storage.IdempotencyStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

@Aspect
@Component
public class IdempotencyAspect implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAspect.class);
    private final IdempotencyStore store;
    private final ExpressionParser parser = new SpelExpressionParser();
    private final ObjectMapper hashMapper;

    @Value("${idempotency.default-ttl:3600}")
    private long globalDefaultTtl;

    @Value("${idempotency.processing-timeout-ms:300000}")
    private long processingTimeoutMs;

    public IdempotencyAspect(IdempotencyStore store) {
        this.store = store;
        // Pre-configure a safe mapper for hashing to avoid GC pressure from frequent .copy() calls
        this.hashMapper = store.getObjectMapper().copy()
                .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Around("@annotation(com.byteentropy.idempotency_core.annotation.Idempotent)")
    public Object handle(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Method method = signature.getMethod();
        Idempotent idempotent = method.getAnnotation(Idempotent.class);

        return execute(joinPoint, idempotent);
    }

    private Object execute(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        String namespace = idempotent.namespace();
        String key = resolveSpel(joinPoint, idempotent.key());
        long finalTtl = (idempotent.ttl() > 0) ? idempotent.ttl() : globalDefaultTtl;

        // Validation
        if (!StringUtils.hasText(namespace)) {
            throw new IllegalArgumentException("Idempotency namespace is required.");
        }
        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("Idempotency key evaluated to empty/null.");
        }

        String currentRequestHash = generateRequestHash(joinPoint.getArgs());

        IdempotencyRecord initial = IdempotencyRecord.builder()
                .status(IdempotencyStatus.PROCESSING)
                .requestHash(currentRequestHash)
                .timestamp(System.currentTimeMillis())
                .build();

        // Atomic check-and-reserve
        Object resultFromStore = store.executeLua(namespace, key, initial, finalTtl);

        if (resultFromStore != null) {
            IdempotencyRecord existing = (IdempotencyRecord) resultFromStore;

            // 1. Handle concurrent processing
            if (existing.getStatus() == IdempotencyStatus.PROCESSING) {
                long elapsed = System.currentTimeMillis() - existing.getTimestamp();
                if (elapsed > processingTimeoutMs) {
                    log.warn("Ghost lock detected for {}:{}. Clearing and retrying.", namespace, key);
                    store.delete(namespace, key);
                    return execute(joinPoint, idempotent);
                }
                throw new RuntimeException("Request is currently being processed.");
            }

            // 2. Validate payload integrity (Same key, different data)
            if (!Objects.equals(existing.getRequestHash(), currentRequestHash)) {
                throw new IllegalStateException("Idempotency Conflict: Key exists with different payload.");
            }

            // 3. Return cached response
            log.info("Returning cached response for {}:{}", namespace, key);
            return existing.getResponse();
        }

        try {
            // Execute business logic
            Object response = joinPoint.proceed();
            
            IdempotencyRecord completed = IdempotencyRecord.builder()
                    .status(IdempotencyStatus.COMPLETED)
                    .response(response)
                    .requestHash(currentRequestHash)
                    .timestamp(System.currentTimeMillis())
                    .build();
            
            store.save(namespace, key, completed, finalTtl);
            return response;
        } catch (Throwable e) {
            // Clean up the lock on failure so the client can retry
            log.error("Execution failed for {}:{}. Clearing lock.", namespace, key);
            store.delete(namespace, key);
            throw e;
        }
    }

    private String resolveSpel(ProceedingJoinPoint joinPoint, String spel) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        EvaluationContext context = new StandardEvaluationContext();
        Object[] args = joinPoint.getArgs();
        String[] paramNames = signature.getParameterNames();
        
        if (paramNames != null) {
            for (int i = 0; i < args.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        context.setVariable("methodName", signature.getMethod().getName());
        return parser.parseExpression(spel).getValue(context, String.class);
    }

    private String generateRequestHash(Object[] args) {
        if (args == null || args.length == 0) return "no-args";

        try {
            StringBuilder sb = new StringBuilder();
            for (Object arg : args) {
                if (arg == null) {
                    sb.append("null");
                } else {
                    sb.append(hashMapper.writeValueAsString(arg));
                }
            }

            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] encodedHash = digest.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(encodedHash);

        } catch (NoSuchAlgorithmException e) {
            log.error("SHA-256 not available, falling back to identity hash.");
            return "fallback-id-" + Objects.hash(args);
        } catch (JsonProcessingException e) {
            log.warn("Serialization for hashing failed. Falling back to identity hash.");
            return "fallback-json-" + Objects.hash(args);
        } catch (Exception e) {
            return "hash-err-" + Objects.hash(args);
        }
    }
}