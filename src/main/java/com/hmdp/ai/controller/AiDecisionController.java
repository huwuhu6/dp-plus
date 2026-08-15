package com.hmdp.ai.controller;

import com.hmdp.ai.dto.DecisionRequest;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.dto.DecisionFollowUpRequest;
import com.hmdp.ai.service.ConsumptionDecisionService;
import com.hmdp.dto.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;

@RestController
@RequestMapping("/ai/decisions")
public class AiDecisionController {
    @Resource
    private ConsumptionDecisionService consumptionDecisionService;

    @PostMapping
    public Result decide(@RequestBody DecisionRequest request) {
        DecisionResponse response = consumptionDecisionService.decide(request);
        return Result.ok(response);
    }

    @GetMapping("/{sessionId}")
    public Result getDecision(@PathVariable Long sessionId) {
        return Result.ok(consumptionDecisionService.getDecision(sessionId));
    }

    @PostMapping("/{sessionId}/messages")
    public Result continueDecision(@PathVariable Long sessionId, @RequestBody DecisionFollowUpRequest request) {
        return Result.ok(consumptionDecisionService.continueDecision(sessionId, request));
    }
}
