package com.hmdp.ai.dto;

import com.hmdp.ai.entity.AiConversationEvaluationCaseResult;
import com.hmdp.ai.entity.AiConversationEvaluationRun;
import lombok.Data;

import java.util.List;

@Data
public class ConversationEvaluationRunResponse {
    private AiConversationEvaluationRun run;
    private List<AiConversationEvaluationCaseResult> caseResults;
}
