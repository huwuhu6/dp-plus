package com.hmdp.ai.v2.semantic;

/** A restricted edge references a preceding observation; it is not a recursive workflow expression. */
public record SemanticRelation(String observedRequestId, ObservationMatcher matcher, String dependentRequestId) {
    public record ObservationMatcher(Operator operator, String expectedValue) { }
    public enum Operator { EQ, GT, GTE, LT, LTE }
}
