package com.hmdp.ai.controller;

import com.hmdp.ai.service.AiEvaluationService;
import com.hmdp.dto.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/ai/evaluations")
public class AiEvaluationController {
    @Resource private AiEvaluationService evaluationService;

    @PostMapping("/runs")
    public Result runActiveCases() {
        return Result.ok(evaluationService.runActiveCases());
    }

    @PostMapping("/runs/holdout")
    public Result runHoldoutCases() {
        return Result.ok(evaluationService.runHoldoutCases());
    }

    @GetMapping("/runs/{runId}")
    public Result getRun(@PathVariable Long runId) {
        return Result.ok(evaluationService.getRun(runId));
    }

    @GetMapping("/runs/{runId}/compare/{baselineRunId}")
    public Result compareRuns(@PathVariable Long runId, @PathVariable Long baselineRunId) {
        return Result.ok(evaluationService.compareRuns(runId, baselineRunId));
    }

    @PostMapping("/runs/{runId}/abort")
    public Result abortRun(@PathVariable Long runId) {
        return Result.ok(evaluationService.abortRun(runId));
    }
}
