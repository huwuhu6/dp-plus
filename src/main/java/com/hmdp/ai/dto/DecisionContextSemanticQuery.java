package com.hmdp.ai.dto;

import lombok.Data;

/** Request-scoped semantic query, before Java resolves references/provenance. */
@Data
public class DecisionContextSemanticQuery {
    public enum Type {
        WHY_RECOMMENDED,
        CONSTRAINT_PROVENANCE,
        CURRENT_CRITERIA,
        EXECUTED_SEARCH_SCOPE
    }

    private Type type;
    private String referenceId;
    private String constraintKey;
}
