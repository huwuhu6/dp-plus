package com.hmdp.ai.runtime;

/** Commands that may advance a decision task lifecycle. */
public enum DecisionCommand {
    START_DECISION,
    EXTRACT_CONSTRAINTS,
    EXECUTE,
    REQUIRE_LOCATION,
    PROVIDE_LOCATION,
    /** UI alias used when a short POI needs current-location disambiguation. */
    USE_DEVICE_LOCATION_FOR_POI_DISAMBIGUATION,
    DECLINE_LOCATION,
    AUTO_RELAXATION,
    EXPAND_RADIUS,
    INCREASE_BUDGET,
    RELAX_CUISINE,
    RELAX_QUIET,
    ALLOW_QUEUE,
    RELAX_LIGHT_TASTE,
    RELAX_HARD_CONSTRAINTS,
    /** User abandons a specific food target while keeping the current search scope. */
    BROADEN_FOOD_SCOPE,
    SWITCH_CITY,
    STRICT_SEARCH_EMPTY,
    NO_DATA_FOUND,
    COMPLETE,
    END_DECISION,
    FAIL
}
