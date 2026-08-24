package com.hmdp.ai.dto;

import lombok.Data;

/** Deterministic gateway decision made after state reduction and before downstream execution. */
@Data
public class PolicyDecision {
    private String action;
    private String reason;
    private String explicitLocationScope;
    private boolean blocking;

    public static PolicyDecision of(String action, String reason) {
        PolicyDecision decision = new PolicyDecision();
        decision.setAction(action);
        decision.setReason(reason);
        return decision;
    }
}
