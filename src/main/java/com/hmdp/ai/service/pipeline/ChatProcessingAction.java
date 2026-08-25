package com.hmdp.ai.service.pipeline;

/**
 * Terminal actions selected by the chat gateway before execution begins.
 * The action is intentionally transport-neutral so HTTP and SSE share one pipeline.
 */
public enum ChatProcessingAction {
    NONE,
    DECISION_EVENT,
    LOCATION_RESOLUTION,
    EXPLAIN_SUSPENDED,
    START_DECISION,
    BUSINESS_FOLLOW_UP,
    EXIT_DECISION,
    GENERAL_CHAT
}
