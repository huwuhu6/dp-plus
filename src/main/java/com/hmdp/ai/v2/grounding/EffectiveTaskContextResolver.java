package com.hmdp.ai.v2.grounding;

import com.hmdp.ai.v2.semantic.EntityReference;
import com.hmdp.ai.v2.semantic.TurnSemantics;
import java.util.List;

public final class EffectiveTaskContextResolver {
    public EffectiveTaskContext resolve(String activeTaskId, List<TaskView> tasks, TurnSemantics semantics) {
        List<TaskView> safe = tasks == null ? List.of() : tasks;
        TaskView active = safe.stream().filter(t -> t.taskId().equals(activeTaskId)).findFirst().orElse(null);
        EntityReference.TaskRef reference = taskReference(semantics);
        if (reference == null) return new EffectiveTaskContext(active, false);
        String needle = reference.description() == null ? "" : reference.description().trim();
        TaskView resolved = safe.stream().filter(t -> matches(t, needle, activeTaskId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("task reference is unresolved: " + needle));
        return new EffectiveTaskContext(resolved, !resolved.taskId().equals(activeTaskId));
    }
    private EntityReference.TaskRef taskReference(TurnSemantics semantics) {
        if (semantics == null) return null;
        EntityReference.TaskRef standalone = semantics.references().stream().filter(EntityReference.TaskRef.class::isInstance)
                .map(EntityReference.TaskRef.class::cast).findFirst().orElse(null);
        if (standalone != null) return standalone;
        return semantics.requests().stream().flatMap(r -> references(r).stream()).filter(EntityReference.TaskRef.class::isInstance)
                .map(EntityReference.TaskRef.class::cast).findFirst().orElse(null);
    }
    private List<EntityReference> references(com.hmdp.ai.v2.semantic.UserRequest request) {
        return switch (request) {
            case com.hmdp.ai.v2.semantic.UserRequest.FactQueryRequest r -> List.of(r.target());
            case com.hmdp.ai.v2.semantic.UserRequest.CompareRequest r -> r.targets();
            case com.hmdp.ai.v2.semantic.UserRequest.SimilarRequest r -> List.of(r.anchor());
            case com.hmdp.ai.v2.semantic.UserRequest.SelectRequest r -> List.of(r.target());
            case com.hmdp.ai.v2.semantic.UserRequest.ExploreRequest r -> List.of(r.target());
            default -> List.of();
        };
    }
    private boolean matches(TaskView task, String needle, String activeTaskId) {
        if (needle.contains("最开始")) return !task.taskId().equals(activeTaskId);
        return task.label() != null && task.label().contains(needle.replace("那个", ""));
    }
}
