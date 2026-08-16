package com.hmdp.ai.controller;

import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.service.ChatOrchestrationService;
import com.hmdp.dto.Result;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.annotation.Resource;

@RestController
@RequestMapping("/ai/chat")
public class AiChatController {
    @Resource private ChatOrchestrationService chatOrchestrationService;

    @PostMapping("/messages")
    public Result message(@RequestBody ChatMessageRequest request) {
        return Result.ok(chatOrchestrationService.chat(request));
    }
}
