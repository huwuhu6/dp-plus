package com.hmdp.ai.v2.semantic;

public sealed interface EntityFeedback permits EntityFeedback.EntityFeedbackItem, EntityFeedback.BatchFeedback {
    record EntityFeedbackItem(EntityReference target, FeedbackKind kind, FeedbackAspect aspect) implements EntityFeedback { }
    /** Batch dislike stays batch-scoped; expanding it into rejects loses user intent. */
    record BatchFeedback(BatchPolarity polarity, FeedbackAspect aspect) implements EntityFeedback { }
    enum FeedbackKind { POSITIVE, CRITIQUE, REJECT, SELECT }
    enum BatchPolarity { POSITIVE, NEGATIVE }
    enum FeedbackAspect { PRICE, DISTANCE, SERVICE, QUEUE, AMBIENCE, UNSPECIFIED }
}
