package com.hmdp.ai.v2.grounding;
public record Ambiguity(Kind kind, String detail) { public enum Kind { UNRESOLVED_REFERENCE, AMBIGUOUS_REFERENCE } }
