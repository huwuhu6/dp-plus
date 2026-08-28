package com.hmdp.ai.controller;

import com.hmdp.ai.dto.DecisionRequest;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.dto.DecisionFollowUpRequest;
import com.hmdp.ai.dto.AgentConversationRequest;
import com.hmdp.ai.service.AgentConversationService;
import com.hmdp.ai.service.ConversationStateService;
import com.hmdp.ai.service.ConsumptionDecisionService;
import com.hmdp.ai.entity.AiChatSession;
import com.hmdp.dto.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import jakarta.annotation.Resource;

@RestController
@RequestMapping("/ai/decisions")
public class AiDecisionController {
    @Resource
    private ConsumptionDecisionService consumptionDecisionService;
    @Resource
    private AgentConversationService agentConversationService;
    @Resource
    private ConversationStateService conversationStateService;
    @Resource
    private com.hmdp.ai.service.IdempotencyService idempotencyService;

    @PostMapping
    public Result decide(@RequestBody DecisionRequest request,
                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        DecisionResponse response = execute(com.hmdp.ai.runtime.IdempotencyScope.DECISION_START, null, idempotencyKey,
                request, DecisionResponse.class, () -> consumptionDecisionService.decide(request));
        return Result.ok(response);
    }

    @GetMapping("/{sessionId}")
    public Result getDecision(@PathVariable Long sessionId) {
        return Result.ok(consumptionDecisionService.getDecision(sessionId));
    }

    @PostMapping("/{sessionId}/messages")
    public Result continueDecision(@PathVariable Long sessionId, @RequestBody DecisionFollowUpRequest request,
                                   @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        java.util.Map<String, Object> command = new java.util.LinkedHashMap<String, Object>();
        command.put("sessionId", sessionId); command.put("request", request);
        String chatId = idempotencyKey == null || idempotencyKey.trim().isEmpty() ? null
                : consumptionDecisionService.decisionChatId(sessionId);
        return Result.ok(execute(com.hmdp.ai.runtime.IdempotencyScope.DECISION_FOLLOW_UP, chatId, idempotencyKey,
                command, DecisionResponse.class, () -> consumptionDecisionService.continueDecision(sessionId, request)));
    }

    @PostMapping("/{sessionId}/conversations")
    public Result conversation(@PathVariable Long sessionId, @RequestBody AgentConversationRequest request) {
        if (request == null || request.getChatId() == null || request.getChatId().trim().isEmpty()) {
            throw new IllegalArgumentException("chatId 不能为空；商户追问必须绑定聊天工作记忆");
        }
        AiChatSession state = conversationStateService.getOrCreate(request.getChatId().trim());
        DecisionResponse decision = consumptionDecisionService.getDecision(sessionId);
        com.hmdp.ai.dto.AgentSessionContext context = conversationStateService.contextForDecision(state, decision);
        com.hmdp.ai.dto.AgentConversationResponse response = agentConversationService.converse(sessionId, request, context);
        conversationStateService.applyAgentContext(state, sessionId, context);
        return Result.ok(response);
    }

    @GetMapping("/{sessionId}/tool-calls")
    public Result toolCalls(@PathVariable Long sessionId) {
        return Result.ok(agentConversationService.getToolCalls(sessionId));
    }

    private <T> T execute(com.hmdp.ai.runtime.IdempotencyScope scope, String chatId, String key, Object request, Class<T> type,
                          java.util.function.Supplier<T> command) {
        if (idempotencyService == null || key == null || key.trim().isEmpty()) return command.get();
        return idempotencyService.execute(scope, chatId, key, request, type, command);
    }
}
