package com.byteentropy.idempotency_core.aspect;

import com.byteentropy.idempotency_core.annotation.Idempotent;
import com.byteentropy.idempotency_core.model.IdempotencyRecord;
import com.byteentropy.idempotency_core.model.IdempotencyStatus;
import com.byteentropy.idempotency_core.storage.IdempotencyStore;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Aspect
@Component
public class IdempotencyAspect {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAspect.class);
    private final IdempotencyStore store;
    private final ExpressionParser parser = new SpelExpressionParser();

    // Injects the global default from properties file. Falls back to 24h if property is missing.
    @Value("${idempotency.default-ttl:86400}")
    private long globalDefaultTtl;

    public IdempotencyAspect(IdempotencyStore store) {
        this.store = store;
    }

    @Around("@annotation(idempotent)")
    public Object handle(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        String key = resolveKey(joinPoint, idempotent.key());
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency key resolved to null or empty");
        }

        // Determine which TTL to use: Annotation override vs Global Default
        long finalTtl = (idempotent.ttl() > 0) ? idempotent.ttl() : globalDefaultTtl;

        String currentRequestHash = generateRequestHash(joinPoint.getArgs());

        IdempotencyRecord initial = IdempotencyRecord.builder()
                .status(IdempotencyStatus.PROCESSING)
                .requestHash(currentRequestHash)
                .timestamp(System.currentTimeMillis())
                .build();

        // 1. Atomically check or reserve the key using the resolved TTL
        Object resultFromLua = store.executeLua(key, initial, finalTtl);

        if (resultFromLua != null) {
            IdempotencyRecord existing = (IdempotencyRecord) resultFromLua;
            
            if (!Objects.equals(existing.getRequestHash(), currentRequestHash)) {
                log.error("Payload mismatch for key {}", key);
                throw new IllegalStateException("Idempotency Key conflict: Different payload provided.");
            }

            if (existing.getStatus() == IdempotencyStatus.PROCESSING) {
                log.warn("Request {} is currently being processed by another thread", key);
                throw new RuntimeException("Request is already in progress.");
            }

            log.info("Returning cached response for key {}", key);
            return existing.getResponse();
        }

        try {
            Object response = joinPoint.proceed();
            
            IdempotencyRecord completed = IdempotencyRecord.builder()
                    .status(IdempotencyStatus.COMPLETED)
                    .response(response)
                    .requestHash(currentRequestHash)
                    .timestamp(System.currentTimeMillis())
                    .build();
            
            store.save(key, completed, finalTtl);
            return response;
        } catch (Exception e) {
            log.error("Error during execution for key {}. Removing idempotency lock.", key);
            store.delete(key);
            throw e;
        }
    }

    private String resolveKey(ProceedingJoinPoint joinPoint, String spel) {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        EvaluationContext context = new StandardEvaluationContext();
        Object[] args = joinPoint.getArgs();
        String[] paramNames = signature.getParameterNames();
        if (paramNames != null) {
            for (int i = 0; i < args.length; i++) {
                context.setVariable(paramNames[i], args[i]);
            }
        }
        return parser.parseExpression(spel).getValue(context, String.class);
    }

    private String generateRequestHash(Object[] args) {
        if (args == null || args.length == 0) return "no-args";
        StringBuilder sb = new StringBuilder();
        for (Object arg : args) {
            sb.append(arg != null ? arg.toString() : "null");
        }
        return DigestUtils.md5DigestAsHex(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}