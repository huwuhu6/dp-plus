package com.hmdp.ai.dto;

import com.hmdp.ai.service.pipeline.ChatProcessingAction;
import lombok.Data;

/** Minimal per-turn plan: criteria mutation is independent from execution routing. */
@Data
public class TurnPlan {
    private CriteriaIntent criteriaIntent = CriteriaIntent.NONE;
    private ChatProcessingAction executionAction = ChatProcessingAction.NONE;

    public TurnPlan() { }

    public TurnPlan(CriteriaIntent criteriaIntent, ChatProcessingAction executionAction) {
        this.criteriaIntent = criteriaIntent == null ? CriteriaIntent.NONE : criteriaIntent;
        this.executionAction = executionAction == null ? ChatProcessingAction.NONE : executionAction;
    }
}
