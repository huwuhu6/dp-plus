package com.hmdp.ai.runtime;

/** Reliability tier is owned by the event type, never selected ad hoc by callers. */
public enum ConversationEventReliability {
    DURABLE,
    BEST_EFFORT
}
