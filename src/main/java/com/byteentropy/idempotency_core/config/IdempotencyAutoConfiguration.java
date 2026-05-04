package com.byteentropy.idempotency_core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

@Configuration
@EnableAspectJAutoProxy
@ComponentScan(basePackages = "com.byteentropy.idempotency_core")
public class IdempotencyAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public DefaultRedisScript<String> idempotencyScript() { // Result type is String
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("scripts/idempotency_check.lua"));
        script.setResultType(String.class);
        return script;
    }

    @Bean
    @ConditionalOnMissingBean
    public StringRedisTemplate redisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory); // Standardizes all data as plain JSON Strings
    }
    
    @Bean
    @ConditionalOnMissingBean
    public ObjectMapper objectMapper() {
        return new ObjectMapper(); 
    }
}