package com.hmdp.ai.v2.semantic;

import java.util.List;

public record TurnSemantics(TaskDirective taskDirective, TaskDirectiveEvidence taskDirectiveEvidence,
                            List<RequirementChange> requirementChanges,
                            List<EntityFeedback> entityFeedback, List<UserRequest> requests,
                            List<SemanticRelation> relations, List<EntityReference> references) {
    public TurnSemantics(TaskDirective taskDirective, List<RequirementChange> requirementChanges,
                         List<EntityFeedback> entityFeedback, List<UserRequest> requests,
                         List<SemanticRelation> relations) {
        this(taskDirective, defaultEvidence(taskDirective), requirementChanges, entityFeedback, requests, relations, List.of());
    }
    public TurnSemantics(TaskDirective taskDirective, List<RequirementChange> requirementChanges,
                         List<EntityFeedback> entityFeedback, List<UserRequest> requests,
                         List<SemanticRelation> relations, List<EntityReference> references) {
        this(taskDirective, defaultEvidence(taskDirective), requirementChanges, entityFeedback, requests, relations, references);
    }
    public TurnSemantics {
        taskDirectiveEvidence = taskDirectiveEvidence == null ? defaultEvidence(taskDirective) : taskDirectiveEvidence;
        requirementChanges = requirementChanges == null ? List.of() : List.copyOf(requirementChanges);
        entityFeedback = entityFeedback == null ? List.of() : List.copyOf(entityFeedback);
        requests = requests == null ? List.of() : List.copyOf(requests);
        relations = relations == null ? List.of() : List.copyOf(relations);
        references = references == null ? List.of() : List.copyOf(references);
    }

    private static TaskDirectiveEvidence defaultEvidence(TaskDirective directive) {
        return switch (directive) {
            case CONTINUE -> TaskDirectiveEvidence.NONE;
            case START_NEW -> TaskDirectiveEvidence.EXPLICIT_NEW_TASK;
            case RESTORE -> TaskDirectiveEvidence.EXPLICIT_RESTORE;
            case ABANDON -> TaskDirectiveEvidence.EXPLICIT_ABANDON;
        };
    }
}
