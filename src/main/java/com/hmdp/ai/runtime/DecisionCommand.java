package com.hmdp.ai.runtime;

/** Commands that may advance a decision task lifecycle. */
public enum DecisionCommand {
    START_DECISION,
    EXTRACT_CONSTRAINTS,
    EXECUTE,
    PROVIDE_LOCATION,
    DECLINE_LOCATION,
    AUTO_RELAXATION,
    EXPAND_RADIUS,
    INCREASE_BUDGET,
    RELAX_CUISINE,
    RELAX_QUIET,
    ALLOW_QUEUE,
    RELAX_LIGHT_TASTE,
    RELAX_HARD_CONSTRAINTS,
    SWITCH_CITY,
    STRICT_SEARCH_EMPTY,
    NO_DATA_FOUND,
    COMPLETE,
    END_DECISION,
    FAIL
}
