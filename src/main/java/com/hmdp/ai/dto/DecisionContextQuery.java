package com.hmdp.ai.dto;

import lombok.Data;

/** Request-scoped, read-only query over the current decision context. */
@Data
public class DecisionContextQuery {
    public enum QueryType {
        WHY_RECOMMENDED,
        CONSTRAINT_PROVENANCE,
        CURRENT_CRITERIA,
        EXECUTED_SEARCH_SCOPE
    }

    private QueryType type;
    private ResolvedShopReference resolvedReference;
    private String constraintKey;
    private String preferenceValue;
}
