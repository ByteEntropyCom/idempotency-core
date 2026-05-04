package com.byteentropy.idempotency_core.aspect;

import com.byteentropy.idempotency_core.annotation.Idempotent;
import com.byteentropy.idempotency_core.model.IdempotencyRecord;
import com.byteentropy.idempotency_core.model.IdempotencyStatus;
import com.byteentropy.idempotency_core.service.IdempotencyService;
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

import java.util.Objects;

@Aspect
@Component
public class IdempotencyAspect implements Ordered {
    private static final Logger log = LoggerFactory.getLogger(IdempotencyAspect.class);
    
    private final IdempotencyService idempotencyService;
    private final ExpressionParser parser = new SpelExpressionParser();

    @Value("${idempotency.default-ttl:3600}")
    private long globalDefaultTtl;

    public IdempotencyAspect(IdempotencyService idempotencyService) {
        this.idempotencyService = idempotencyService;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Around("@annotation(com.byteentropy.idempotency_core.annotation.Idempotent)")
    public Object handle(ProceedingJoinPoint joinPoint) throws Throwable {
        MethodSignature signature = (MethodSignature) joinPoint.getSignature();
        Idempotent idempotent = signature.getMethod().getAnnotation(Idempotent.class);

        String namespace = idempotent.namespace();
        String key = resolveSpel(joinPoint, idempotent.key());
        long finalTtl = (idempotent.ttl() > 0) ? idempotent.ttl() : globalDefaultTtl;

        if (!StringUtils.hasText(namespace) || !StringUtils.hasText(key)) {
            throw new IllegalArgumentException("Idempotency namespace and key are required.");
        }

        String currentHash = idempotencyService.generateHash(joinPoint.getArgs());
        IdempotencyRecord existing = idempotencyService.attemptReservation(namespace, key, currentHash, finalTtl);

        if (existing != null) {
            if (existing.getStatus() == IdempotencyStatus.PROCESSING) {
                long elapsed = System.currentTimeMillis() - existing.getTimestamp();
                if (elapsed > idempotencyService.getProcessingTimeoutMs()) {
                    log.warn("Ghost lock detected for {}:{}. Retrying.", namespace, key);
                    idempotencyService.rollback(namespace, key);
                    return handle(joinPoint);
                }
                throw new RuntimeException("Request is currently being processed.");
            }

            if (!Objects.equals(existing.getRequestHash(), currentHash)) {
                throw new IllegalStateException("Idempotency Conflict: Key exists with different payload.");
            }

            return existing.getResponse();
        }

        try {
            Object response = joinPoint.proceed();
            idempotencyService.commit(namespace, key, currentHash, response, finalTtl);
            return response;
        } catch (Throwable e) {
            idempotencyService.rollback(namespace, key);
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
}