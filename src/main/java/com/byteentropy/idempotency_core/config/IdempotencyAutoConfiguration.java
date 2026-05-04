package com.byteentropy.idempotency_core.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.byteentropy.idempotency_core.model.IdempotencyRecord;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableAspectJAutoProxy
@ComponentScan(basePackages = "com.byteentropy.idempotency_core")
public class IdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DefaultRedisScript<String> idempotencyScript() {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/idempotency_check.lua"));
        script.setResultType(String.class);
        return script;
    }

    @Bean
    @ConditionalOnMissingBean
    public StringRedisTemplate redisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
    
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper(); 
    }

    @Bean
    @ConditionalOnMissingBean
    public Cache<String, IdempotencyRecord> caffeineCache() {
        // We set a maximum size and a default expiration as a fallback
        return Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.HOURS) 
                .maximumSize(10000)
                .build();
    }
}