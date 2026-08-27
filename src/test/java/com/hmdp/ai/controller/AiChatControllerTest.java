package com.hmdp.ai.controller;

import com.hmdp.ai.dto.ChatMessageRequest;
import com.hmdp.ai.service.ChatStreamService;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiChatControllerTest {
    @Test
    void configuresStreamingResponseAndDelegatesToStreamService() {
        AiChatController controller = new AiChatController();
        ChatStreamService streamService = mock(ChatStreamService.class);
        SseEmitter emitter = new SseEmitter();
        ReflectionTestUtils.setField(controller, "chatStreamService", streamService);
        when(streamService.stream(any(ChatMessageRequest.class), isNull())).thenReturn(emitter);
        MockHttpServletResponse response = new MockHttpServletResponse();

        SseEmitter actual = controller.streamMessage(new ChatMessageRequest(), response);

        assertSame(emitter, actual);
        assertEquals("no", response.getHeader("X-Accel-Buffering"));
        assertEquals("no-cache", response.getHeader("Cache-Control"));
    }
}
