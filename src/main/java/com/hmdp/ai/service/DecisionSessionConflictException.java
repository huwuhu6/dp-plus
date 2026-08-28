package com.hmdp.ai.service;

public class DecisionSessionConflictException extends RuntimeException {
    public DecisionSessionConflictException(Long sessionId, String expectedStatus) {
        super("Decision session changed concurrently: sessionId=" + sessionId + ", expectedStatus=" + expectedStatus);
    }
}
