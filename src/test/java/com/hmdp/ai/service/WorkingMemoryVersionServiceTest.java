package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.entity.AiConversationEvent;
import com.hmdp.ai.entity.AiWorkingMemory;
import com.hmdp.ai.mapper.AiConversationEventMapper;
import com.hmdp.ai.mapper.AiWorkingMemoryMapper;
import com.hmdp.ai.runtime.ConversationEventType;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkingMemoryVersionServiceTest {
    @Test
    void appendsMemoryAndStateEventWithTheSameSnapshotId() {
        AiWorkingMemoryMapper memoryMapper = mock(AiWorkingMemoryMapper.class);
        AiConversationEventMapper eventMapper = mock(AiConversationEventMapper.class);
        ConversationEventService eventService = mock(ConversationEventService.class);
        when(eventService.newStateEvent(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            AiConversationEvent event = new AiConversationEvent();
            event.setWorkingMemoryId(invocation.getArgument(2));
            return event;
        });
        doAnswer(invocation -> { invocation.getArgument(0, AiWorkingMemory.class).setId(101L); return 1; })
                .when(memoryMapper).insert(any(AiWorkingMemory.class));

        WorkingMemoryVersionService service = service(memoryMapper, eventMapper, eventService);
        AiWorkingMemory persisted = service.append("chat-1", 9L, 0, new ConversationWorkingMemory(),
                ConversationEventType.STATE_REDUCED, "INITIAL_MEMORY_CREATED", null);

        assertEquals(101L, persisted.getId());
        assertEquals(1, persisted.getVersion());
        assertEquals(9L, persisted.getUserId());
        verify(eventMapper).insert(any(AiConversationEvent.class));
    }

    @Test
    void propagatesOptimisticVersionConflictWithoutWritingAnEvent() {
        AiWorkingMemoryMapper memoryMapper = mock(AiWorkingMemoryMapper.class);
        AiConversationEventMapper eventMapper = mock(AiConversationEventMapper.class);
        ConversationEventService eventService = mock(ConversationEventService.class);
        when(memoryMapper.insert(any(AiWorkingMemory.class))).thenThrow(new DuplicateKeyException("duplicate version"));

        WorkingMemoryVersionService service = service(memoryMapper, eventMapper, eventService);
        assertThrows(IllegalStateException.class, () -> service.append("chat-1", 9L, 3,
                new ConversationWorkingMemory(), ConversationEventType.STATE_REDUCED, "UPDATE", null));
        verify(eventMapper, org.mockito.Mockito.never()).insert(any(AiConversationEvent.class));
    }

    @Test
    void loadsOnlyTheLatestVersion() {
        AiWorkingMemoryMapper memoryMapper = mock(AiWorkingMemoryMapper.class);
        AiWorkingMemory expected = new AiWorkingMemory(); expected.setVersion(4);
        when(memoryMapper.selectOne(any(QueryWrapper.class))).thenReturn(expected);
        WorkingMemoryVersionService service = service(memoryMapper, mock(AiConversationEventMapper.class), mock(ConversationEventService.class));
        assertEquals(4, service.latest("chat-1").getVersion());
    }

    @Test
    void readsOnlyAnExactHistoricalVersionAndReturnsDetachedCopy() {
        AiWorkingMemoryMapper memoryMapper = mock(AiWorkingMemoryMapper.class);
        AiWorkingMemory stored = new AiWorkingMemory();
        stored.setId(7L); stored.setChatId("chat-1"); stored.setVersion(3); stored.setMemoryJson("{\"dialogPhase\":\"IDLE\"}");
        when(memoryMapper.selectOne(any(QueryWrapper.class))).thenReturn(stored);
        WorkingMemoryVersionService service = service(memoryMapper, mock(AiConversationEventMapper.class), mock(ConversationEventService.class));

        AiWorkingMemory snapshot = service.get("chat-1", 3);

        snapshot.setMemoryJson("{}");
        assertEquals("{\"dialogPhase\":\"IDLE\"}", stored.getMemoryJson());
        assertEquals(3, snapshot.getVersion());
    }

    @Test
    void rejectsMissingHistoricalVersionInsteadOfFallingBackToLatest() {
        AiWorkingMemoryMapper memoryMapper = mock(AiWorkingMemoryMapper.class);
        when(memoryMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        WorkingMemoryVersionService service = service(memoryMapper, mock(AiConversationEventMapper.class), mock(ConversationEventService.class));

        assertThrows(IllegalArgumentException.class, () -> service.get("chat-1", 99));
    }

    @Test
    void rejectsHistoricalSnapshotOwnedByAnotherUser() {
        AiWorkingMemoryMapper memoryMapper = mock(AiWorkingMemoryMapper.class);
        AiWorkingMemory stored = new AiWorkingMemory();
        stored.setChatId("chat-1"); stored.setVersion(3); stored.setUserId(9L);
        when(memoryMapper.selectOne(any(QueryWrapper.class))).thenReturn(stored);
        WorkingMemoryVersionService service = service(memoryMapper, mock(AiConversationEventMapper.class), mock(ConversationEventService.class));

        assertThrows(SecurityException.class, () -> service.get("chat-1", 3));
    }

    private WorkingMemoryVersionService service(AiWorkingMemoryMapper memoryMapper, AiConversationEventMapper eventMapper,
                                                ConversationEventService eventService) {
        WorkingMemoryVersionService service = new WorkingMemoryVersionService();
        ReflectionTestUtils.setField(service, "workingMemoryMapper", memoryMapper);
        ReflectionTestUtils.setField(service, "eventMapper", eventMapper);
        ReflectionTestUtils.setField(service, "eventService", eventService);
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper().findAndRegisterModules());
        return service;
    }
}
