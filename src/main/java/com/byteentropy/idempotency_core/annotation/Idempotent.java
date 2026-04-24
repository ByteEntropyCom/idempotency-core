package com.byteentropy.idempotency_core.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {
    /** SpEL expression for the key */
    String key();

    /** * Time to live in seconds. 
     * Default is -1, which triggers the use of the global property.
     */
    long ttl() default -1;
}