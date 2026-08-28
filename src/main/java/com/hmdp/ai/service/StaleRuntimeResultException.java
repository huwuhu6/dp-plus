package com.hmdp.ai.service;

import lombok.Getter;

@Getter
public class StaleRuntimeResultException extends IllegalStateException {
    private final String chatId;
    private final int baseVersion;
    private final int actualVersion;
    private final Long decisionSessionId;

    public StaleRuntimeResultException(String chatId, int baseVersion, int actualVersion, Long decisionSessionId) {
        super("Agent runtime result is stale: chatId=" + chatId + ", baseVersion=" + baseVersion
                + ", actualVersion=" + actualVersion + ", decisionSessionId=" + decisionSessionId);
        this.chatId = chatId;
        this.baseVersion = baseVersion;
        this.actualVersion = actualVersion;
        this.decisionSessionId = decisionSessionId;
    }
}
