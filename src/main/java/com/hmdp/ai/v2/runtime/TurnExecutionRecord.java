package com.hmdp.ai.v2.runtime;

import java.time.Instant;

/** V2 runtime persistence contract: unique(chatId, turnId), separate from WorkingMemory OCC versioning. */
public record TurnExecutionRecord(String chatId, String turnId, Status status, Integer committedVersion,
                                  String responseReferenceOrHash, Instant createdAt, Instant updatedAt) {
    public enum Status { RECEIVED, EXECUTING, COMMITTED, FAILED }
}
