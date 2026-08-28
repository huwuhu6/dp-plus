package com.hmdp.ai.runtime;

/** Named effects expected after a valid state transition. */
public enum DecisionSideEffect {
    PERSIST_REQUEST,
    EXTRACT_CONSTRAINTS,
    REQUIRE_LOCATION,
    APPLY_LOCATION,
    APPLY_RELAXATION,
    RETRY_SEARCH,
    PERSIST_PENDING_OPTIONS,
    CLEAR_PENDING_OPTIONS,
    PERSIST_RESULT,
    CANCEL_TASK,
    REQUEST_NEW_SEARCH,
    RECORD_FAILURE
}
