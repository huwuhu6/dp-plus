package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ChatMemoryServiceTest {
    @Test
    void appendsOneTurnThroughASingleRedisScript() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ChatMemoryService service = new ChatMemoryService();
        ReflectionTestUtils.setField(service, "stringRedisTemplate", redis);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper().findAndRegisterModules());

        service.appendTurn("chat-atomic", "用户消息", "助手消息", "GENERAL_CHAT", null);

        verify(redis).execute(any(org.springframework.data.redis.core.script.DefaultRedisScript.class),
                eq(List.of("ai:chat:memory:v2:chat-atomic")), any(), any(), any(), any());
    }
}
