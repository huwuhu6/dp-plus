package com.hmdp.ai.v2.runtime;

/** A controlled user-facing failure; callers must clarify instead of entering a legacy semantic path. */
public class SemanticInterpretationException extends RuntimeException {
    public SemanticInterpretationException(String message, Throwable cause) {
        super(message, cause);
    }
}
