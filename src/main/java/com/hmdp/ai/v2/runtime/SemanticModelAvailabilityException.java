package com.hmdp.ai.v2.runtime;

/** Controlled transport/model-availability failure; it is not eligible for semantic repair. */
public class SemanticModelAvailabilityException extends RuntimeException {
    public SemanticModelAvailabilityException(String message, Throwable cause) { super(message, cause); }
}
