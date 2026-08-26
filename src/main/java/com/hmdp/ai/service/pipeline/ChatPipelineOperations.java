package com.hmdp.ai.service.pipeline;

/**
 * Domain-operation boundary used by nodes. Implementations own dependencies, while
 * nodes only control progression and never persist state directly.
 */
public interface ChatPipelineOperations {
    void bootstrap(ChatProcessingContext context);
    void rewrite(ChatProcessingContext context);
    void route(ChatProcessingContext context);
    void reduceCriteria(ChatProcessingContext context);
    void applyPolicyGuard(ChatProcessingContext context);
    void execute(ChatProcessingContext context);
}
