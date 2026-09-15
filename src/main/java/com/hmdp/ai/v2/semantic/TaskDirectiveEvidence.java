package com.hmdp.ai.v2.semantic;

/** Explicit user operation required to authorize a non-continuation task directive. */
public enum TaskDirectiveEvidence {
    NONE, EXPLICIT_NEW_TASK, EXPLICIT_RESTORE, EXPLICIT_ABANDON
}
