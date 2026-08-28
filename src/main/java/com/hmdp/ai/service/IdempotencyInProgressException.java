package com.hmdp.ai.service;

public class IdempotencyInProgressException extends RuntimeException {
    public IdempotencyInProgressException(String scope, String key) {
        super("Idempotency command is still processing: scope=" + scope + ", key=" + key);
    }
}
