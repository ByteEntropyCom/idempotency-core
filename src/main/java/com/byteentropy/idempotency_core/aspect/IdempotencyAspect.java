package com.byteentropy.idempotency_core.aspect;

import com.byteentropy.idempotency_core.annotation.Idempotent;
import com.byteentropy.idempotency_core.model.IdempotencyRecord;
import com.byteentropy.idempotency_core.model.IdempotencyStatus;
import com.byteentropy.idempotency_core.storage.IdempotencyStore;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.util.DigestUtils;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Aspect
@Component
public class IdempotencyAspect implements Ordered {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyAspect.class);
    private final IdempotencyStore store;
    private final ExpressionParser parser = new SpelExpressionParser();

    @Value("${idempotency.default-ttl:3600}")
    private long globalDefaultTtl;

    @Value("${idempotency.processing-timeout-ms:300000}")
    private long processingTimeoutMs;

    public IdempotencyAspect(IdempotencyStore store) {
        this.store = store;
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

        // STRICT VALIDATION: No fallbacks
        if (!StringUtils.hasText(namespace)) {
            throw new IllegalArgumentException("Idempotency namespace is required and cannot be empty.");
        }

        if (!StringUtils.hasText(key)) {
            throw new IllegalArgumentException("Idempotency key evaluated to empty/null");
        }

        String currentRequestHash = generateRequestHash(joinPoint.getArgs());

        IdempotencyRecord initial = IdempotencyRecord.builder()
                .status(IdempotencyStatus.PROCESSING)
                .requestHash(currentRequestHash)
                .timestamp(System.currentTimeMillis())
                .build();

        Object resultFromStore = store.executeLua(namespace, key, initial, finalTtl);

        if (resultFromStore != null) {
            IdempotencyRecord existing = (IdempotencyRecord) resultFromStore;

            if (existing.getStatus() == IdempotencyStatus.PROCESSING) {
                long elapsed = System.currentTimeMillis() - existing.getTimestamp();
                if (elapsed > processingTimeoutMs) {
                    log.warn("Ghost lock detected for {}:{}. Clearing and retrying.", namespace, key);
                    store.delete(namespace, key);
                    return execute(joinPoint, idempotent);
                }
                throw new RuntimeException("Request is currently being processed.");
            }

            if (!Objects.equals(existing.getRequestHash(), currentRequestHash)) {
                throw new IllegalStateException("Idempotency Conflict: Key exists with different payload.");
            }

            log.info("Returning cached response for {}:{}", namespace, key);
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
            
            store.save(namespace, key, completed, finalTtl);
            return response;
        } catch (Throwable e) {
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
            ObjectMapper mapper = store.getObjectMapper();
            for (Object arg : args) {
                sb.append(arg != null ? mapper.writeValueAsString(arg) : "null");
            }
            return DigestUtils.md5DigestAsHex(sb.toString().getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return "hash-" + Objects.hash(args);
        }
    }
}