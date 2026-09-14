package com.hmdp.ai.service;

import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.ChatStreamEventData;
import com.hmdp.ai.v2.runtime.V2ChatOrchestrator;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.function.Consumer;

/** Application boundary retained for the existing HTTP and SSE entry points. */
@Service
public class ChatOrchestrationService {
    @Resource private ChatMemoryService chatMemoryService;
    @Resource private V2ChatOrchestrator v2ChatOrchestrator;

    public ChatMessageResponse chat(ChatMessageRequest request) {
        return v2ChatOrchestrator.chat(request);
    }

    /** Resolves the conversation scope before request-idempotency hashes the request. */
    public String resolveChatId(ChatMessageRequest request) {
        if (request == null) throw new IllegalArgumentException("request cannot be null");
        String chatId = chatMemoryService.resolveChatId(request.getChatId());
        request.setChatId(chatId);
        return chatId;
    }

    public ChatMessageResponse chat(ChatMessageRequest request, Consumer<String> textDeltaConsumer) {
        return chat(request, textDeltaConsumer, null);
    }

    /** Preserves the application signature used by SSE and evaluation callers. */
    public ChatMessageResponse chat(ChatMessageRequest request, Consumer<String> textDeltaConsumer,
                                    Consumer<ChatStreamEventData> eventConsumer) {
        return v2ChatOrchestrator.chat(request);
    }
}
