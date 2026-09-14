package com.hmdp.ai.v2.reducer;

import com.hmdp.ai.v2.semantic.EntityFeedback;

/** Durable, intent-preserving feedback. Batch feedback never expands into entity rejects. */
public record V2FeedbackEntry(Scope scope, Long shopId, String batchId, EntityFeedback.FeedbackKind kind,
                              EntityFeedback.BatchPolarity batchPolarity, EntityFeedback.FeedbackAspect aspect) {
    public enum Scope { ENTITY, BATCH }
}
