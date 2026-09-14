package com.hmdp.ai.v2.evidence;

/** Lightweight provenance retained with a recommendation, not a full retrieval/debug trace. */
public record DecisionReason(String reasonType, String criterionRef, String observedValue, String evidenceRef) { }
