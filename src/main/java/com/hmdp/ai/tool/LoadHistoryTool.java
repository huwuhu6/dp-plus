package com.hmdp.ai.tool;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.entity.AiConversationEvent;
import com.hmdp.ai.entity.AiWorkingMemory;
import com.hmdp.ai.mapper.AiConversationEventMapper;
import com.hmdp.ai.service.ChatMemoryService;
import com.hmdp.ai.service.ConversationStateService;
import com.hmdp.ai.service.WorkingMemoryVersionService;
import org.springframework.stereotype.Component;

import jakarta.annotation.Resource;
import java.util.LinkedHashMap;
import java.util.Map;

/** Read-only historical state inspection. It never creates a restore command or mutates state. */
@Component
public class LoadHistoryTool extends BaseAgentTool {
    @Resource private WorkingMemoryVersionService workingMemoryVersionService;
    @Resource private ConversationStateService conversationStateService;
    @Resource private AiConversationEventMapper conversationEventMapper;
    @Resource private ChatMemoryService chatMemoryService;

    @Override public String name() { return "load_history"; }
    @Override public String description() { return "Read one confirmed historical conversation working-memory version without restoring it."; }

    @Override public Map<String, Object> parameterSchema() {
        Map<String, Object> properties = new LinkedHashMap<String, Object>();
        properties.put("chatId", property("string", "Conversation chat id."));
        properties.put("version", property("integer", "Exact historical working-memory version."));
        return objectSchema(properties, "chatId", "version");
    }

    @Override public AgentToolResult execute(Map<String, Object> input) {
        String chatId = input.get("chatId") == null ? "" : String.valueOf(input.get("chatId")).trim();
        Integer version = integer(input.get("version"));
        if (chatId.isEmpty() || version == null) throw new IllegalArgumentException("load_history requires chatId and version");
        AiWorkingMemory snapshot = workingMemoryVersionService.get(chatId, version);
        ConversationWorkingMemory memory = conversationStateService.historicalWorkingMemory(snapshot.getMemoryJson());
        Map<String, Object> facts = new LinkedHashMap<String, Object>();
        facts.put("sourceVersion", snapshot.getVersion());
        facts.put("createdAt", snapshot.getCreatedAt());
        facts.put("activeDecisionSessionId", memory.getActiveDecisionSessionId());
        facts.put("sourceDecisionSessionId", memory.getSourceDecisionSessionId());
        facts.put("relevantState", summary(memory));
        facts.put("linkedStateEvent", linkedEvent(snapshot.getId()));
        facts.put("recentMessages", chatMemoryService.load(chatId));
        facts.put("restorableFields", java.util.Arrays.asList("activeCriteria", "candidatePool", "shownShopIds",
                "focusedShopId", "focusedShopName", "location", "searchLocation", "activeDecisionSessionId",
                "lastDecisionSessionId", "sourceDecisionSessionId", "dialogPhase", "pendingLocationCandidates"));
        facts.put("warnings", java.util.Collections.singletonList("Read-only result. Restore requires a separate confirmed command."));
        AgentToolResult result = new AgentToolResult().summary("Loaded historical working memory version " + version)
                .displayText("已读取历史状态版本 " + version + "，尚未执行恢复。");
        result.setFacts(facts);
        return result;
    }

    private Map<String, Object> linkedEvent(Long workingMemoryId) {
        AiConversationEvent event = conversationEventMapper.selectOne(new QueryWrapper<AiConversationEvent>()
                .eq("working_memory_id", workingMemoryId).orderByDesc("id").last("limit 1"));
        if (event == null) return null;
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("id", event.getId()); result.put("eventType", event.getEventType());
        result.put("metadata", event.getMetadata()); result.put("createdAt", event.getCreatedAt());
        return result;
    }

    private Map<String, Object> summary(ConversationWorkingMemory memory) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("dialogPhase", memory.getDialogPhase());
        result.put("candidateCount", memory.getCandidatePool().size());
        result.put("shownShopIds", memory.getShownShopIds());
        result.put("focusedShopId", memory.getFocusedShopId());
        result.put("activeCriteria", memory.getActiveCriteria());
        return result;
    }

    private Integer integer(Object value) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value == null || String.valueOf(value).trim().isEmpty()) return null;
        return Integer.valueOf(String.valueOf(value));
    }
}
