package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.entity.AiConversationEvent;
import com.hmdp.ai.entity.AiWorkingMemory;
import com.hmdp.ai.mapper.AiConversationEventMapper;
import com.hmdp.ai.mapper.AiWorkingMemoryMapper;
import com.hmdp.ai.runtime.ConversationEventType;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class WorkingMemoryVersionService {
    @Resource private AiWorkingMemoryMapper workingMemoryMapper;
    @Resource private AiConversationEventMapper eventMapper;
    @Resource private ConversationEventService eventService;
    @Resource private ObjectMapper objectMapper;

    public AiWorkingMemory latest(String chatId) {
        return workingMemoryMapper.selectOne(new QueryWrapper<AiWorkingMemory>()
                .eq("chat_id", chatId).orderByDesc("version").last("limit 1"));
    }

    /** Reads one exact historical row and returns a detached copy. */
    public AiWorkingMemory get(String chatId, int version) {
        AiWorkingMemory row = workingMemoryMapper.selectOne(new QueryWrapper<AiWorkingMemory>()
                .eq("chat_id", chatId).eq("version", version).last("limit 1"));
        if (row == null) throw new IllegalArgumentException("Requested working memory version does not exist");
        ensureOwner(row);
        AiWorkingMemory copy = new AiWorkingMemory();
        copy.setId(row.getId()); copy.setChatId(row.getChatId()); copy.setUserId(row.getUserId());
        copy.setVersion(row.getVersion()); copy.setMemoryJson(row.getMemoryJson()); copy.setCreatedAt(row.getCreatedAt());
        return copy;
    }

    @Transactional
    public AiWorkingMemory append(String chatId, Long userId, int expectedVersion, Object memory,
                                  ConversationEventType eventType, Object eventResult, Map<String, Object> metadata) {
        AiWorkingMemory row = new AiWorkingMemory();
        row.setChatId(chatId); row.setUserId(userId); row.setVersion(expectedVersion + 1);
        row.setMemoryJson(write(memory)); row.setCreatedAt(LocalDateTime.now());
        try {
            workingMemoryMapper.insert(row);
        } catch (DuplicateKeyException e) {
            throw new IllegalStateException("Conversation working memory changed concurrently", e);
        }
        AiConversationEvent event = eventService.newStateEvent(eventService.currentTrace(), eventType,
                row.getId(), eventResult, metadata);
        eventMapper.insert(event);
        return row;
    }

    private String write(Object memory) {
        try { return objectMapper.writeValueAsString(memory); }
        catch (Exception e) { throw new IllegalStateException("Conversation working memory cannot be written", e); }
    }

    private void ensureOwner(AiWorkingMemory row) {
        if (row.getUserId() == null) return;
        if (UserHolder.getUser() == null || !row.getUserId().equals(UserHolder.getUser().getId())) {
            throw new SecurityException("No permission to access this chat working memory");
        }
    }
}
