package com.hmdp.ai.service;

import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.dto.RestoreConversationStateCommand;
import com.hmdp.ai.dto.RestoreConversationStateResult;
import com.hmdp.ai.entity.AiChatSession;
import com.hmdp.ai.entity.AiDecisionSession;
import com.hmdp.ai.entity.AiWorkingMemory;
import com.hmdp.ai.mapper.AiDecisionSessionMapper;
import com.hmdp.utils.UserHolder;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

/** Coordinates confirmed restores without mutating a historical snapshot or DecisionSession. */
@Service
public class ConversationStateRestoreService {
    @Resource private WorkingMemoryVersionService workingMemoryVersionService;
    @Resource private ConversationStateService conversationStateService;
    @Resource private AiDecisionSessionMapper decisionSessionMapper;
    @Resource private IdempotencyService idempotencyService;

    public RestoreConversationStateResult restore(RestoreConversationStateCommand command) {
        if (idempotencyService == null) return restoreInternal(command);
        return idempotencyService.execute(com.hmdp.ai.runtime.IdempotencyScope.RESTORE, command == null ? null : command.getCommandId(),
                command, RestoreConversationStateResult.class, () -> restoreInternal(command));
    }

    private RestoreConversationStateResult restoreInternal(RestoreConversationStateCommand command) {
        validate(command);
        String chatId = command.getChatId().trim();
        // Validate the requested immutable source before any operation can initialize state.
        AiWorkingMemory source = workingMemoryVersionService.get(chatId, command.getSourceVersion());
        AiChatSession current = conversationStateService.getOrCreate(chatId);
        int actualVersion = current.getVersion() == null ? 0 : current.getVersion();
        if (actualVersion != command.getExpectedCurrentVersion()) {
            throw new RestoreVersionConflictException(command.getSourceVersion(), command.getExpectedCurrentVersion(), actualVersion);
        }

        ConversationWorkingMemory restored = conversationStateService.historicalWorkingMemory(source.getMemoryJson());
        RestoreConversationStateResult result = new RestoreConversationStateResult();
        result.setSourceVersion(command.getSourceVersion());
        result.setExpectedCurrentVersion(command.getExpectedCurrentVersion());
        result.setActualCurrentVersion(actualVersion);
        validateDecisionReferences(chatId, restored, result);

        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("restoredFromVersion", command.getSourceVersion());
        metadata.put("restoreReason", command.getReason());
        metadata.put("commandId", command.getCommandId());
        metadata.put("requestedBy", UserHolder.getUser() == null ? null : UserHolder.getUser().getId());
        metadata.put("previousVersion", actualVersion);
        metadata.put("newVersion", actualVersion + 1);
        AiWorkingMemory committed;
        try {
            committed = conversationStateService.restoreBusinessState(current, restored, metadata);
        } catch (IllegalStateException e) {
            AiWorkingMemory latest = workingMemoryVersionService.latest(chatId);
            int latestVersion = latest == null || latest.getVersion() == null ? 0 : latest.getVersion();
            if (latestVersion != actualVersion) {
                throw new RestoreVersionConflictException(command.getSourceVersion(), command.getExpectedCurrentVersion(), latestVersion);
            }
            throw e;
        }
        result.setNewVersion(committed.getVersion());
        result.setWorkingMemoryId(committed.getId());
        return result;
    }

    private void validate(RestoreConversationStateCommand command) {
        if (command == null || !hasText(command.getChatId()) || command.getSourceVersion() == null
                || command.getExpectedCurrentVersion() == null || !hasText(command.getReason())
                || !hasText(command.getCommandId())) {
            throw new IllegalArgumentException("Restore command is incomplete");
        }
        if (!RestoreConversationStateCommand.BUSINESS_STATE.equals(command.getScope())) {
            throw new IllegalArgumentException("Only BUSINESS_STATE restore is supported");
        }
    }

    private void validateDecisionReferences(String chatId, ConversationWorkingMemory memory,
                                            RestoreConversationStateResult result) {
        memory.setActiveDecisionSessionId(validDecision(chatId, memory.getActiveDecisionSessionId(), "activeDecisionSessionId", result));
        memory.setLastDecisionSessionId(validDecision(chatId, memory.getLastDecisionSessionId(), "lastDecisionSessionId", result));
        memory.setSourceDecisionSessionId(validDecision(chatId, memory.getSourceDecisionSessionId(), "sourceDecisionSessionId", result));
    }

    private Long validDecision(String chatId, Long sessionId, String field, RestoreConversationStateResult result) {
        if (sessionId == null) return null;
        AiDecisionSession session = decisionSessionMapper.selectById(sessionId);
        if (session == null || !chatId.equals(session.getChatId()) || !isOwned(session)) {
            result.getWarnings().add(field + " was cleared because the referenced decision session is unavailable");
            return null;
        }
        if ("COMPLETED".equals(session.getStatus()) || "CANCELLED".equals(session.getStatus())) {
            result.getWarnings().add(field + " references terminal decision session " + sessionId + "; its lifecycle was not changed");
        }
        return sessionId;
    }

    private boolean isOwned(AiDecisionSession session) {
        return session.getUserId() == null || (UserHolder.getUser() != null && session.getUserId().equals(UserHolder.getUser().getId()));
    }

    private boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
}
