package com.hmdp.ai.service;

import lombok.Getter;

@Getter
public class VersionConflictException extends IllegalStateException {
    private final String chatId;
    private final int expectedVersion;
    private final int actualVersion;
    private final String operationType;

    public VersionConflictException(String chatId, int expectedVersion, int actualVersion, String operationType) {
        super("Working memory version conflict: chatId=" + chatId + ", expectedVersion=" + expectedVersion
                + ", actualVersion=" + actualVersion + ", operationType=" + operationType);
        this.chatId = chatId;
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
        this.operationType = operationType;
    }
}
