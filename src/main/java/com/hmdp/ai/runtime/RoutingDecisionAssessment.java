package com.hmdp.ai.runtime;

import com.hmdp.ai.service.pipeline.ChatProcessingAction;
import com.hmdp.ai.dto.TurnPlan;
import lombok.Data;

/** Transient evidence for why a chat route was accepted or escalated. */
@Data
public class RoutingDecisionAssessment {
    private ChatProcessingAction candidateAction;
    private String source;
    private boolean contextRequired;
    private boolean contextResolved;
    private boolean requiredContextMissing;
    private boolean conflictDetected;
    private boolean stateAllowed;
    private boolean shouldEscalate;
    private String reason;
    private TurnPlan turnPlan = new TurnPlan();
}
