package com.hmdp.ai.controller;

import com.hmdp.ai.dto.DecisionRequest;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.dto.DecisionFollowUpRequest;
import com.hmdp.ai.dto.AgentConversationRequest;
import com.hmdp.ai.service.AgentConversationService;
import com.hmdp.ai.service.ConsumptionDecisionService;
import com.hmdp.dto.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

@RestController
@RequestMapping("/ai/decisions")
public class AiDecisionController {
    @Resource
    private ConsumptionDecisionService consumptionDecisionService;
    @Resource
    private AgentConversationService agentConversationService;

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

    @PostMapping("/{sessionId}/conversations")
    public Result conversation(@PathVariable Long sessionId, @RequestBody AgentConversationRequest request) {
        return Result.ok(agentConversationService.converse(sessionId, request));
    }

    @GetMapping("/{sessionId}/tool-calls")
    public Result toolCalls(@PathVariable Long sessionId) {
        return Result.ok(agentConversationService.getToolCalls(sessionId));
    }
}
