package com.hmdp.ai.tool;

/** Defines whether a tool can safely run against an immutable conversation snapshot. */
public enum ToolExecutionMode {
    PARALLEL_SAFE,
    SEQUENTIAL_STATEFUL
}
