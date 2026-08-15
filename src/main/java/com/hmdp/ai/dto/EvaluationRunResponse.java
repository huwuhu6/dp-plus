package com.hmdp.ai.dto;

import com.hmdp.ai.entity.AiEvaluationCaseResult;
import com.hmdp.ai.entity.AiEvaluationRun;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class EvaluationRunResponse {
    private AiEvaluationRun run;
    private List<AiEvaluationCaseResult> caseResults = new ArrayList<>();
}
