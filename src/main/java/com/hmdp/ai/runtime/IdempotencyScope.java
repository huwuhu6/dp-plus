package com.hmdp.ai.runtime;

public enum IdempotencyScope {
    CHAT_MESSAGE,
    DECISION_START,
    DECISION_FOLLOW_UP,
    RESTORE,
    TOOL_INVOCATION
}
