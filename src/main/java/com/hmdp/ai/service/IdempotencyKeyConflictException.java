package com.hmdp.ai.service;

public class IdempotencyKeyConflictException extends RuntimeException {
    public IdempotencyKeyConflictException(String scope, String key) {
        super("Idempotency key was already used with a different request: scope=" + scope + ", key=" + key);
    }
}
