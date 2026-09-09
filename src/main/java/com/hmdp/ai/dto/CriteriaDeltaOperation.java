package com.hmdp.ai.dto;

import lombok.Data;

/** Raw semantic mutation. Canonical values and policy remain Java-owned. */
@Data
public class CriteriaDeltaOperation {
    public enum Field {
        BUDGET_PER_PERSON,
        RADIUS_KM,
        NEARBY,
        CUISINE,
        EXCLUDED_CUISINE,
        KEYWORD,
        PREFERENCE,
        ARRIVAL_TIME
    }

    public enum Operation { SET, INCREASE, DECREASE, ADD, REMOVE, CLEAR }

    private Field field;
    private Operation operation;
    private String rawValue;
    private String anchorReferenceId;
    private SemanticEvidence evidence;
}
