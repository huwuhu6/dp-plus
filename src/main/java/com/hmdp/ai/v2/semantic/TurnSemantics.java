package com.hmdp.ai.v2.semantic;

import java.util.List;

public record TurnSemantics(TaskDirective taskDirective, List<RequirementChange> requirementChanges,
                            List<EntityFeedback> entityFeedback, List<UserRequest> requests,
                            List<SemanticRelation> relations, List<EntityReference> references) {
    public TurnSemantics(TaskDirective taskDirective, List<RequirementChange> requirementChanges,
                         List<EntityFeedback> entityFeedback, List<UserRequest> requests,
                         List<SemanticRelation> relations) {
        this(taskDirective, requirementChanges, entityFeedback, requests, relations, List.of());
    }
    public TurnSemantics {
        requirementChanges = requirementChanges == null ? List.of() : List.copyOf(requirementChanges);
        entityFeedback = entityFeedback == null ? List.of() : List.copyOf(entityFeedback);
        requests = requests == null ? List.of() : List.copyOf(requests);
        relations = relations == null ? List.of() : List.copyOf(relations);
        references = references == null ? List.of() : List.copyOf(references);
    }
}
