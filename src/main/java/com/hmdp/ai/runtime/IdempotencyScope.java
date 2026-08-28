package com.hmdp.ai.runtime;

public enum IdempotencyScope {
    CHAT_MESSAGE(true),
    /** Direct /ai/decisions requests are standalone and do not carry a chatId. */
    DECISION_START(false),
    DECISION_FOLLOW_UP(true),
    RESTORE(true),
    /** Reserved for future state-changing tools invoked from an Agent chat context. */
    TOOL_INVOCATION(true);

    private final boolean chatScoped;

    IdempotencyScope(boolean chatScoped) {
        this.chatScoped = chatScoped;
    }

    public boolean isChatScoped() {
        return chatScoped;
    }
}
