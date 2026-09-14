package com.hmdp.ai.v2.plan;
public sealed interface CompilationResult permits CompilationResult.Direct, CompilationResult.StaticPlan, CompilationResult.Adaptive {
    record Direct(ExecutionAction action) implements CompilationResult { }
    record StaticPlan(ExecutionPlan plan) implements CompilationResult { }
    record Adaptive(AdaptiveResearchContract contract) implements CompilationResult { }
}
