package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.entity.AiConversationEvent;
import com.hmdp.ai.mapper.AiConversationEventMapper;
import com.hmdp.ai.runtime.ConversationEventStatus;
import com.hmdp.ai.runtime.ConversationEventType;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.dao.DuplicateKeyException;

import java.util.concurrent.atomic.AtomicReference;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationEventServiceTest {
    @Test
    void persistsDurableInputDirectlyWithoutUsingTheBestEffortBuffer() {
        AiConversationEventMapper mapper = mock(AiConversationEventMapper.class);
        when(mapper.insert(any(AiConversationEvent.class))).thenReturn(1);
        ConversationEventService service = service(mapper);
        service.begin("chat-durable", 1);

        service.persistDurableEvent(ConversationEventType.USER_INPUT, ConversationEventStatus.SUCCESS,
                null, null, Map.of("content", "help me find dinner"), null);

        verify(mapper).insert(any(AiConversationEvent.class));
        service.clearTrace();
    }

    @Test
    void rejectsDurableEventOnBestEffortPath() {
        ConversationEventService service = service(mock(AiConversationEventMapper.class));
        service.begin("chat-durable", 1);

        assertThrows(IllegalArgumentException.class, () -> service.recordBestEffort(
                ConversationEventType.ASSISTANT_OUTPUT, ConversationEventStatus.SUCCESS, null, null, Map.of(), null));
        service.clearTrace();
    }

    @Test
    void failsDurableWriteWhenMapperReportsNoInsertedRow() {
        AiConversationEventMapper mapper = mock(AiConversationEventMapper.class);
        when(mapper.insert(any(AiConversationEvent.class))).thenReturn(0);
        ConversationEventService service = service(mapper);
        service.begin("chat-durable", 1);

        assertThrows(IllegalStateException.class, () -> service.persistDurableEvent(
                ConversationEventType.ASSISTANT_OUTPUT, ConversationEventStatus.SUCCESS, null, null, Map.of(), null));
        service.clearTrace();
    }

    @Test
    void replaysTheSameDurableEventWhenItsIdentityAlreadyExists() {
        AiConversationEventMapper mapper = mock(AiConversationEventMapper.class);
        AtomicReference<AiConversationEvent> attempted = new AtomicReference<AiConversationEvent>();
        org.mockito.Mockito.doAnswer(invocation -> {
            attempted.set(invocation.getArgument(0));
            throw new DuplicateKeyException("duplicate event");
        }).when(mapper).insert(any(AiConversationEvent.class));
        when(mapper.selectById(any())).thenAnswer(invocation -> copy(attempted.get()));
        ConversationEventService service = service(mapper);
        service.begin("chat-durable", 1);

        Long eventId = service.persistDurableEvent(ConversationEventType.USER_INPUT, ConversationEventStatus.SUCCESS,
                null, null, Map.of("content", "same request"), null);

        assertEquals(attempted.get().getId(), eventId);
        service.clearTrace();
    }

    @Test
    void rejectsDuplicateIdentityWithDifferentPayload() {
        AiConversationEventMapper mapper = mock(AiConversationEventMapper.class);
        AtomicReference<AiConversationEvent> attempted = new AtomicReference<AiConversationEvent>();
        org.mockito.Mockito.doAnswer(invocation -> {
            attempted.set(invocation.getArgument(0));
            throw new DuplicateKeyException("duplicate event");
        }).when(mapper).insert(any(AiConversationEvent.class));
        when(mapper.selectById(any())).thenAnswer(invocation -> {
            AiConversationEvent existing = copy(attempted.get());
            existing.setEventResult("{\"content\":\"different request\"}");
            return existing;
        });
        ConversationEventService service = service(mapper);
        service.begin("chat-durable", 1);

        assertThrows(IllegalStateException.class, () -> service.persistDurableEvent(
                ConversationEventType.USER_INPUT, ConversationEventStatus.SUCCESS, null, null, Map.of("content", "same request"), null));
        service.clearTrace();
    }

    private AiConversationEvent copy(AiConversationEvent source) {
        AiConversationEvent result = new AiConversationEvent();
        result.setId(source.getId()); result.setChatId(source.getChatId()); result.setTraceId(source.getTraceId());
        result.setTurnNo(source.getTurnNo()); result.setSequenceNo(source.getSequenceNo()); result.setEventType(source.getEventType());
        result.setStatus(source.getStatus()); result.setWorkingMemoryId(source.getWorkingMemoryId());
        result.setParentEventId(source.getParentEventId()); result.setEventResult(source.getEventResult());
        result.setMetadata(source.getMetadata()); return result;
    }

    private ConversationEventService service(AiConversationEventMapper mapper) {
        ConversationEventService service = new ConversationEventService();
        ReflectionTestUtils.setField(service, "eventMapper", mapper);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper().findAndRegisterModules());
        return service;
    }
}
