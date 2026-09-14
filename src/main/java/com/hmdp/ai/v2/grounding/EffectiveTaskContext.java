package com.hmdp.ai.v2.grounding;

/** Resolving a historical TaskRef only chooses this turn's read scope; durable RESTORE is a later reducer mutation. */
public record EffectiveTaskContext(TaskView task, boolean restoredForThisTurn) { }
