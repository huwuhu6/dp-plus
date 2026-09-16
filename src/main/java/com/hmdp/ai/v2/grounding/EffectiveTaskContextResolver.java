package com.hmdp.ai.v2.grounding;

import com.hmdp.ai.v2.semantic.EntityReference;
import com.hmdp.ai.v2.semantic.TurnSemantics;
import java.util.Comparator;
import java.util.List;
import com.hmdp.ai.v2.semantic.TaskSelector;
import com.hmdp.ai.v2.semantic.TaskDirective;

public final class EffectiveTaskContextResolver {
    public EffectiveTaskContextResult resolveResult(String activeTaskId, List<TaskView> tasks, TurnSemantics semantics) {
        try { return new EffectiveTaskContextResult.Resolved(resolve(activeTaskId, tasks, semantics)); }
        catch (TaskIssue issue) { return new EffectiveTaskContextResult.NeedsClarification(new Ambiguity(issue.kind, issue.getMessage())); }
    }
    public EffectiveTaskContext resolve(String activeTaskId, List<TaskView> tasks, TurnSemantics semantics) {
        List<TaskView> safe = tasks == null ? List.of() : tasks;
        TaskView active = safe.stream().filter(t -> t.taskId().equals(activeTaskId)).findFirst().orElse(null);
        EntityReference.TaskRef reference = taskReference(semantics);
        if (reference == null) {
            if (semantics != null && semantics.taskDirective() == TaskDirective.RESTORE)
                throw new TaskIssue(Ambiguity.Kind.UNRESOLVED_REFERENCE, "RESTORE target is not uniquely identified");
            return new EffectiveTaskContext(active, false);
        }
        TaskView resolved = resolve(reference.selector(), activeTaskId, safe);
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
    private TaskView resolve(TaskSelector selector, String activeTaskId, List<TaskView> tasks) {
        List<TaskView> matches = switch (selector) {
            case TaskSelector.Earliest ignored -> tasks.stream().filter(t -> t.taskId() != null).sorted(Comparator.comparingInt(TaskView::creationOrder)).limit(1).toList();
            case TaskSelector.Active ignored -> tasks.stream().filter(t -> t.taskId().equals(activeTaskId)).toList();
            case TaskSelector.MatchContext context -> tasks.stream().filter(t -> equal(t.goalCategory(), context.goalCategory()) && equal(t.city(), context.city())).toList();
        };
        if (matches.isEmpty()) throw new TaskIssue(Ambiguity.Kind.UNRESOLVED_REFERENCE, "task selector matches no task");
        if (matches.size() > 1) throw new TaskIssue(Ambiguity.Kind.AMBIGUOUS_REFERENCE, "task selector matches multiple tasks");
        return matches.getFirst();
    }
    private boolean equal(String left, String right) { return left == null ? right == null : left.equals(right); }
    private static final class TaskIssue extends RuntimeException {
        private final Ambiguity.Kind kind;
        private TaskIssue(Ambiguity.Kind kind, String message) { super(message); this.kind = kind; }
    }
}
