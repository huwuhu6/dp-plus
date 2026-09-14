package com.hmdp.ai.v2.verification;

/** Capability metadata belongs to the system, not to a user's preference. */
public record CriterionCapability(String criterion, Verifiability verifiability) {
    public enum Verifiability { DETERMINISTIC, EVIDENCE_BASED, UNVERIFIABLE }
}
