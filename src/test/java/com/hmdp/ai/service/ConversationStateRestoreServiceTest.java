package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.dto.RestoreConversationStateCommand;
import com.hmdp.ai.dto.RestoreConversationStateResult;
import com.hmdp.ai.entity.AiChatSession;
import com.hmdp.ai.entity.AiDecisionSession;
import com.hmdp.ai.entity.AiWorkingMemory;
import com.hmdp.ai.mapper.AiDecisionSessionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ConversationStateRestoreServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void restoresExactSourceAsANewVersionWithAuditableMetadata() throws Exception {
        WorkingMemoryVersionService versions = mock(WorkingMemoryVersionService.class);
        ConversationStateService states = mock(ConversationStateService.class);
        AiDecisionSessionMapper decisions = mock(AiDecisionSessionMapper.class);
        AiChatSession current = state("restore-chat", 11);
        AiWorkingMemory source = source("restore-chat", 10, memory());
        AiWorkingMemory committed = source("restore-chat", 12, memory()); committed.setId(120L);
        when(states.getOrCreate("restore-chat")).thenReturn(current);
        when(versions.get("restore-chat", 10)).thenReturn(source);
        when(states.historicalWorkingMemory(source.getMemoryJson())).thenReturn(memory());
        when(states.restoreBusinessState(eq(current), any(ConversationWorkingMemory.class), any(Map.class))).thenReturn(committed);

        RestoreConversationStateResult result = service(versions, states, decisions).restore(command(10, 11));

        assertEquals(10, result.getSourceVersion());
        assertEquals(11, result.getExpectedCurrentVersion());
        assertEquals(11, result.getActualCurrentVersion());
        assertEquals(12, result.getNewVersion());
        assertEquals(120L, result.getWorkingMemoryId());
        org.mockito.ArgumentCaptor<Map<String, Object>> metadata = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(states).restoreBusinessState(eq(current), any(ConversationWorkingMemory.class), metadata.capture());
        assertEquals(10, metadata.getValue().get("restoredFromVersion"));
        assertEquals(11, metadata.getValue().get("previousVersion"));
        assertEquals(12, metadata.getValue().get("newVersion"));
        assertEquals("restore-command-1", metadata.getValue().get("commandId"));
    }

    @Test
    void rejectsConflictWithoutReadingOrWritingHistoricalState() throws Exception {
        WorkingMemoryVersionService versions = mock(WorkingMemoryVersionService.class);
        ConversationStateService states = mock(ConversationStateService.class);
        AiChatSession current = state("restore-chat", 12);
        when(versions.get("restore-chat", 10)).thenReturn(source("restore-chat", 10, memory()));
        when(states.getOrCreate("restore-chat")).thenReturn(current);

        RestoreVersionConflictException error = assertThrows(RestoreVersionConflictException.class,
                () -> service(versions, states, mock(AiDecisionSessionMapper.class)).restore(command(10, 11)));

        assertEquals(10, error.getSourceVersion());
        assertEquals(11, error.getExpectedCurrentVersion());
        assertEquals(12, error.getActualCurrentVersion());
        verify(states, never()).restoreBusinessState(any(), any(), any());
    }

    @Test
    void translatesWriteTimeCasConflictToRestoreConflict() throws Exception {
        WorkingMemoryVersionService versions = mock(WorkingMemoryVersionService.class);
        ConversationStateService states = mock(ConversationStateService.class);
        AiChatSession current = state("restore-chat", 11);
        AiWorkingMemory source = source("restore-chat", 10, memory());
        AiWorkingMemory newer = source("restore-chat", 12, memory());
        when(versions.get("restore-chat", 10)).thenReturn(source);
        when(states.getOrCreate("restore-chat")).thenReturn(current);
        when(states.historicalWorkingMemory(source.getMemoryJson())).thenReturn(memory());
        when(states.restoreBusinessState(eq(current), any(), any())).thenThrow(new IllegalStateException("concurrent write"));
        when(versions.latest("restore-chat")).thenReturn(newer);

        RestoreVersionConflictException error = assertThrows(RestoreVersionConflictException.class,
                () -> service(versions, states, mock(AiDecisionSessionMapper.class)).restore(command(10, 11)));

        assertEquals(12, error.getActualCurrentVersion());
    }

    @Test
    void clearsMissingDecisionReferenceWithoutCreatingAGhostSession() throws Exception {
        WorkingMemoryVersionService versions = mock(WorkingMemoryVersionService.class);
        ConversationStateService states = mock(ConversationStateService.class);
        AiDecisionSessionMapper decisions = mock(AiDecisionSessionMapper.class);
        AiChatSession current = state("restore-chat", 11);
        ConversationWorkingMemory historical = memory();
        historical.setActiveDecisionSessionId(99L);
        AiWorkingMemory source = source("restore-chat", 10, historical);
        AiWorkingMemory committed = source("restore-chat", 12, historical);
        when(states.getOrCreate("restore-chat")).thenReturn(current);
        when(versions.get("restore-chat", 10)).thenReturn(source);
        when(states.historicalWorkingMemory(source.getMemoryJson())).thenReturn(historical);
        when(states.restoreBusinessState(eq(current), any(), any())).thenReturn(committed);
        when(decisions.selectById(99L)).thenReturn(null);

        RestoreConversationStateResult result = service(versions, states, decisions).restore(command(10, 11));

        assertNull(historical.getActiveDecisionSessionId());
        assertEquals(1, result.getWarnings().size());
    }

    @Test
    void preservesTerminalDecisionReferenceWithoutChangingItsLifecycle() throws Exception {
        WorkingMemoryVersionService versions = mock(WorkingMemoryVersionService.class);
        ConversationStateService states = mock(ConversationStateService.class);
        AiDecisionSessionMapper decisions = mock(AiDecisionSessionMapper.class);
        AiChatSession current = state("restore-chat", 11);
        ConversationWorkingMemory historical = memory(); historical.setActiveDecisionSessionId(99L);
        AiWorkingMemory source = source("restore-chat", 10, historical);
        AiWorkingMemory committed = source("restore-chat", 12, historical);
        AiDecisionSession terminal = new AiDecisionSession(); terminal.setId(99L); terminal.setChatId("restore-chat"); terminal.setStatus("COMPLETED");
        when(states.getOrCreate("restore-chat")).thenReturn(current);
        when(versions.get("restore-chat", 10)).thenReturn(source);
        when(states.historicalWorkingMemory(source.getMemoryJson())).thenReturn(historical);
        when(states.restoreBusinessState(eq(current), any(), any())).thenReturn(committed);
        when(decisions.selectById(99L)).thenReturn(terminal);

        RestoreConversationStateResult result = service(versions, states, decisions).restore(command(10, 11));

        assertEquals(99L, historical.getActiveDecisionSessionId());
        assertEquals(1, result.getWarnings().size());
        verify(decisions, never()).updateById(any(AiDecisionSession.class));
    }

    private ConversationStateRestoreService service(WorkingMemoryVersionService versions, ConversationStateService states,
                                                    AiDecisionSessionMapper decisions) {
        ConversationStateRestoreService service = new ConversationStateRestoreService();
        ReflectionTestUtils.setField(service, "workingMemoryVersionService", versions);
        ReflectionTestUtils.setField(service, "conversationStateService", states);
        ReflectionTestUtils.setField(service, "decisionSessionMapper", decisions);
        return service;
    }

    private RestoreConversationStateCommand command(int sourceVersion, int expectedVersion) {
        RestoreConversationStateCommand command = new RestoreConversationStateCommand();
        command.setChatId("restore-chat"); command.setSourceVersion(sourceVersion); command.setExpectedCurrentVersion(expectedVersion);
        command.setReason("user_confirmed"); command.setCommandId("restore-command-1");
        return command;
    }

    private AiChatSession state(String chatId, int version) throws Exception {
        AiChatSession state = new AiChatSession(); state.setChatId(chatId); state.setVersion(version);
        state.setWorkingMemoryJson(objectMapper.writeValueAsString(memory()));
        return state;
    }

    private AiWorkingMemory source(String chatId, int version, ConversationWorkingMemory memory) throws Exception {
        AiWorkingMemory row = new AiWorkingMemory(); row.setChatId(chatId); row.setVersion(version);
        row.setMemoryJson(objectMapper.writeValueAsString(memory));
        return row;
    }

    private ConversationWorkingMemory memory() { return new ConversationWorkingMemory(); }
}
