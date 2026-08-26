package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.entity.AiConversationEvent;
import com.hmdp.ai.entity.AiWorkingMemory;
import com.hmdp.ai.mapper.AiConversationEventMapper;
import com.hmdp.ai.mapper.AiWorkingMemoryMapper;
import com.hmdp.ai.runtime.ConversationEventType;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.Resource;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** Verifies the V40 runtime tables with real Mapper and transaction wiring. */
@SpringBootTest
class WorkingMemoryRuntimePersistenceIntegrationTest {
    @Resource private WorkingMemoryVersionService workingMemoryVersionService;
    @Resource private ConversationEventService conversationEventService;
    @Resource private AiWorkingMemoryMapper workingMemoryMapper;
    @Resource private AiConversationEventMapper conversationEventMapper;

    @Test
    @Transactional
    void stateMutationPersistsMemoryVersionAndLinkedEventAtomically() {
        String chatId = "runtime-persistence-it-" + System.nanoTime();
        conversationEventService.begin(chatId, 1);
        try {
            ConversationWorkingMemory memory = new ConversationWorkingMemory();
            memory.setDialogPhase("RECOMMENDING");

            AiWorkingMemory version = workingMemoryVersionService.append(chatId, 1L, 0, memory,
                    ConversationEventType.STATE_REDUCED,
                    Collections.<String, Object>singletonMap("dialogPhase", "RECOMMENDING"),
                    Collections.<String, Object>singletonMap("source", "integration-test"));

            AiWorkingMemory persistedMemory = workingMemoryMapper.selectById(version.getId());
            AiConversationEvent event = conversationEventMapper.selectOne(new QueryWrapper<AiConversationEvent>()
                    .eq("working_memory_id", version.getId())
                    .eq("event_type", ConversationEventType.STATE_REDUCED.name()));

            assertNotNull(persistedMemory);
            assertEquals(1, persistedMemory.getVersion());
            assertNotNull(event);
            assertEquals(version.getId(), event.getWorkingMemoryId());
            assertEquals(chatId, event.getChatId());
        } finally {
            conversationEventService.clearTrace();
        }
    }
}
