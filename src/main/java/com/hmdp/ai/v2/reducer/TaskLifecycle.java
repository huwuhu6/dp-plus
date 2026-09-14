package com.hmdp.ai.v2.reducer;

/** Durable task state. It is deliberately distinct from a one-turn TaskDirective. */
public enum TaskLifecycle { ACTIVE, SUSPENDED, COMPLETED, ABANDONED }
