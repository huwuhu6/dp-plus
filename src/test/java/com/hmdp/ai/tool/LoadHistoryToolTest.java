package com.hmdp.ai.tool;

import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.entity.AiWorkingMemory;
import com.hmdp.ai.mapper.AiConversationEventMapper;
import com.hmdp.ai.service.ChatMemoryService;
import com.hmdp.ai.service.ConversationStateService;
import com.hmdp.ai.service.WorkingMemoryVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * LoadHistoryTool 定点覆盖（2026-09-03，GLM 评审"主链路 + 无测试"风险项）。
 * 该工具通过 @Resource List<BaseAgentTool> 自动注册进模型可见工具集，但无显式引导场景，
 * 属于"备而未用"：钉住只读快照契约（restorableFields/warnings），防止未来改坏。
 */
class LoadHistoryToolTest {
    private LoadHistoryTool tool;
    private WorkingMemoryVersionService v;
    private ConversationStateService s;
    private AiConversationEventMapper m;
    private ChatMemoryService c;

    @BeforeEach
    void setUp() {
        tool = new LoadHistoryTool();
        v = mock(WorkingMemoryVersionService.class);
        s = mock(ConversationStateService.class);
        m = mock(AiConversationEventMapper.class);
        c = mock(ChatMemoryService.class);
        ReflectionTestUtils.setField(tool, "workingMemoryVersionService", v);
        ReflectionTestUtils.setField(tool, "conversationStateService", s);
        ReflectionTestUtils.setField(tool, "conversationEventMapper", m);
        ReflectionTestUtils.setField(tool, "chatMemoryService", c);
    }

    @Test
    void loadHistoryToolReturnsReadOnlySnapshot() {
        AiWorkingMemory snapshot = new AiWorkingMemory();
        snapshot.setId(1L);
        snapshot.setVersion(3);
        snapshot.setMemoryJson("{}");
        snapshot.setCreatedAt(LocalDateTime.now());
        when(v.get("chat-1", 3)).thenReturn(snapshot);
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        memory.setDialogPhase("COMPLETED");
        memory.setCandidatePool(new ArrayList<com.hmdp.ai.dto.DecisionRecommendation>());
        memory.setShownShopIds(new ArrayList<Long>());
        when(s.historicalWorkingMemory("{}")).thenReturn(memory);
        when(c.load("chat-1")).thenReturn(Collections.emptyList());

        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("chatId", "chat-1");
        input.put("version", 3);
        AgentToolResult result = tool.execute(input);

        assertEquals("Loaded historical working memory version 3", result.getSummary());
        Map<String, Object> facts = result.getFacts();
        assertEquals(3, facts.get("sourceVersion"));
        assertNotNull(facts.get("restorableFields"));
        assertNotNull(facts.get("recentMessages"));
        // 只读契约：warnings 明确"不执行恢复"，不产生状态变更
        assertTrue(((List<?>) facts.get("warnings")).get(0).toString().contains("Read-only"));
        assertTrue(((List<?>) facts.get("restorableFields")).contains("activeCriteria"));
    }

    @Test
    void loadHistoryToolRejectsMissingParameters() {
        Map<String, Object> noChatId = new LinkedHashMap<String, Object>();
        noChatId.put("version", 1);
        assertThrows(IllegalArgumentException.class, () -> tool.execute(noChatId));

        Map<String, Object> noVersion = new LinkedHashMap<String, Object>();
        noVersion.put("chatId", "c");
        assertThrows(IllegalArgumentException.class, () -> tool.execute(noVersion));
    }

    @Test
    void loadHistoryToolPropagatesMissingVersion() {
        when(v.get("c", 99)).thenThrow(new IllegalArgumentException("Requested working memory version does not exist"));
        Map<String, Object> input = new LinkedHashMap<String, Object>();
        input.put("chatId", "c");
        input.put("version", 99);
        assertThrows(IllegalArgumentException.class, () -> tool.execute(input));
    }
}
