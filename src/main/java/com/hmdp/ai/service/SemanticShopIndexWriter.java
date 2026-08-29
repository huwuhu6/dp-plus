package com.hmdp.ai.service;

import com.hmdp.ai.entity.AiVectorSyncTask;

/** Applies a persisted vector task. Network failures are surfaced to the task worker for retry. */
public interface SemanticShopIndexWriter {
    void apply(AiVectorSyncTask task);
}
