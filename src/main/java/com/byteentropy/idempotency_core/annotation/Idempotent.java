package com.byteentropy.idempotency_core.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    /** SpEL expression for the key */
    String key();

    /** 
     * Categorize keys (e.g., "payments", "orders"). 
     * Prevents key collisions between different services.
     */
    String namespace() default "default";

    /** 
     * Time to live in seconds. 
     * Default is -1, triggers the use of global property.
     */
    long ttl() default -1;
}