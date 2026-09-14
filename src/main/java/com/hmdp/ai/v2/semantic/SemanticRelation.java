package com.hmdp.ai.v2.semantic;

/** A restricted edge references a preceding observation; it is not a recursive workflow expression. */
public record SemanticRelation(String observedRequestId, ObservationPredicate predicate, String dependentRequestId) { }
