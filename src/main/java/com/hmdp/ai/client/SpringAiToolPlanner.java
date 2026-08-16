package com.hmdp.ai.client;

import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.service.AiModelCallTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class SpringAiToolPlanner {
    private static final Logger log = LoggerFactory.getLogger(SpringAiToolPlanner.class);

    @Resource @Qualifier("agentChatClient") private ChatClient chatClient;
    @Resource private AiProperties aiProperties;
    @Resource private AiModelCallTracker modelCallTracker;

    public ToolPlan plan(List<Map<String, Object>> rawMessages, List<ToolCallback> callbacks, String action) {
        long startedAt = System.currentTimeMillis();
        log.info("[AI][spring-ai] action={} model={} tools={} event=REQUEST", action, aiProperties.getModel(), callbacks.size());
        try {
            ChatResponse response = chatClient.prompt()
                    .messages(toMessages(rawMessages))
                    .toolCallbacks(callbacks)
                    .options(OpenAiChatOptions.builder()
                            .model(aiProperties.getModel())
                            .temperature(0.1)
                            .internalToolExecutionEnabled(false)
                            .build())
                    .call()
                    .chatResponse();
            if (response == null || !response.hasToolCalls()) {
                modelCallTracker.recordSuccess();
                log.info("[AI][spring-ai] action={} event=NO_TOOL_CALL durationMs={}", action,
                        System.currentTimeMillis() - startedAt);
                return ToolPlan.empty();
            }
            List<AssistantMessage.ToolCall> calls = response.getResult().getOutput().getToolCalls();
            if (calls == null || calls.isEmpty()) return ToolPlan.empty();
            AssistantMessage.ToolCall call = calls.get(0);
            modelCallTracker.recordSuccess();
            log.info("[AI][spring-ai] action={} event=TOOL_PLAN durationMs={} tool={}", action,
                    System.currentTimeMillis() - startedAt, call.name());
            return new ToolPlan(call.name(), call.arguments());
        } catch (RuntimeException e) {
            modelCallTracker.recordFailure();
            log.warn("[AI][spring-ai] action={} event=FAILURE durationMs={} errorType={}", action,
                    System.currentTimeMillis() - startedAt, e.getClass().getSimpleName());
            throw e;
        }
    }

    private List<Message> toMessages(List<Map<String, Object>> rawMessages) {
        if (rawMessages == null) return Collections.emptyList();
        List<Message> messages = new ArrayList<Message>();
        for (Map<String, Object> raw : rawMessages) {
            String role = String.valueOf(raw.get("role")).toLowerCase();
            String content = String.valueOf(raw.getOrDefault("content", ""));
            if ("system".equals(role)) messages.add(new SystemMessage(content));
            else if ("assistant".equals(role)) messages.add(new AssistantMessage(content));
            else messages.add(new UserMessage(content));
        }
        return messages;
    }

    public static final class ToolPlan {
        private final String name;
        private final String arguments;

        private ToolPlan(String name, String arguments) {
            this.name = name;
            this.arguments = arguments;
        }

        public static ToolPlan empty() { return new ToolPlan(null, null); }
        public boolean isEmpty() { return name == null || name.isBlank(); }
        public String getName() { return name; }
        public String getArguments() { return arguments == null || arguments.isBlank() ? "{}" : arguments; }
    }
}
