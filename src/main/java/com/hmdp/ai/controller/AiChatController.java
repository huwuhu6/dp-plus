package com.hmdp.ai.controller;

import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.service.ChatOrchestrationService;
import com.hmdp.ai.service.ChatStreamService;
import com.hmdp.dto.Result;
import com.hmdp.utils.UserHolder;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.Resource;

@RestController
@RequestMapping("/ai/chat")
public class AiChatController {
    @Resource private ChatOrchestrationService chatOrchestrationService;
    @Resource private ChatStreamService chatStreamService;

    @PostMapping("/messages")
    public Result message(@RequestBody ChatMessageRequest request) {
        return Result.ok(chatOrchestrationService.chat(request));
    }

    @PostMapping(value = "/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamMessage(@RequestBody ChatMessageRequest request) {
        return chatStreamService.stream(request, UserHolder.getUser());
    }
}
