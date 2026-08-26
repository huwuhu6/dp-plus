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
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Component
public class SpringAiTextClient {
    private static final Logger log = LoggerFactory.getLogger(SpringAiTextClient.class);

    @Resource
    @Qualifier("agentChatClient")
    private ChatClient chatClient;
    @Resource private AiProperties aiProperties;
    @Resource private AiModelCallTracker modelCallTracker;

    public String chatText(List<Map<String, Object>> rawMessages, String action) {
        long startedAt = System.currentTimeMillis();
        log.info("[AI][spring-ai] action={} model={} messages={} event=REQUEST", action,
                aiProperties.getModel(), rawMessages == null ? 0 : rawMessages.size());
        try {
            ChatResponse response = chatClient.prompt().messages(toMessages(rawMessages)).call().chatResponse();
            String content = response == null || response.getResult() == null ? null : response.getResult().getOutput().getText();
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("Model did not return text content");
            }
            modelCallTracker.recordSuccess(promptTokens(response), completionTokens(response));
            log.info("[AI][spring-ai] action={} model={} event=SUCCESS durationMs={} chars={}", action,
                    aiProperties.getModel(), System.currentTimeMillis() - startedAt, content.length());
            return content;
        } catch (RuntimeException e) {
            modelCallTracker.recordFailure();
            log.warn("[AI][spring-ai] action={} model={} event=FAILURE durationMs={} errorType={}", action,
                    aiProperties.getModel(), System.currentTimeMillis() - startedAt, e.getClass().getSimpleName());
            throw e;
        }
    }

    public String chatText(List<Map<String, Object>> rawMessages, String action, Integer timeoutMs) {
        return chatText(rawMessages, action);
    }

    /**
     * Streams provider text chunks to the caller while retaining the complete answer for
     * memory persistence and post-generation guards in the orchestration layer.
     */
    public String streamText(List<Map<String, Object>> rawMessages, String action, Consumer<String> onDelta) {
        long startedAt = System.currentTimeMillis();
        log.info("[AI][spring-ai] action={} model={} messages={} event=STREAM_REQUEST", action,
                aiProperties.getModel(), rawMessages == null ? 0 : rawMessages.size());
        StringBuilder answer = new StringBuilder();
        try {
            chatClient.prompt().messages(toMessages(rawMessages)).stream().content()
                    .filter(chunk -> chunk != null && !chunk.isEmpty())
                    .doOnNext(chunk -> {
                        answer.append(chunk);
                        if (onDelta != null) onDelta.accept(chunk);
                    })
                    .blockLast();
            if (answer.isEmpty()) throw new IllegalStateException("Model did not return streamed text content");
            modelCallTracker.recordSuccess(null, null);
            log.info("[AI][spring-ai] action={} model={} event=STREAM_SUCCESS durationMs={} chars={}", action,
                    aiProperties.getModel(), System.currentTimeMillis() - startedAt, answer.length());
            return answer.toString();
        } catch (RuntimeException e) {
            modelCallTracker.recordFailure();
            log.warn("[AI][spring-ai] action={} model={} event=STREAM_FAILURE durationMs={} errorType={}", action,
                    aiProperties.getModel(), System.currentTimeMillis() - startedAt, e.getClass().getSimpleName());
            throw e;
        }
    }

    private List<Message> toMessages(List<Map<String, Object>> rawMessages) {
        List<Message> messages = new ArrayList<Message>();
        if (rawMessages == null) return messages;
        for (Map<String, Object> raw : rawMessages) {
            String role = String.valueOf(raw.get("role")).toLowerCase();
            String content = String.valueOf(raw.getOrDefault("content", ""));
            if ("system".equals(role)) messages.add(new SystemMessage(content));
            else if ("assistant".equals(role)) messages.add(new AssistantMessage(content));
            else messages.add(new UserMessage(content));
        }
        return messages;
    }

    private Integer promptTokens(ChatResponse response) {
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        return usage == null ? null : usage.getPromptTokens();
    }

    private Integer completionTokens(ChatResponse response) {
        Usage usage = response == null || response.getMetadata() == null ? null : response.getMetadata().getUsage();
        return usage == null ? null : usage.getCompletionTokens();
    }
}
