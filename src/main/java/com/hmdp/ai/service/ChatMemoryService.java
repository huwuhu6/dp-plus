package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.entity.AiConversationEvent;
import com.hmdp.ai.mapper.AiConversationEventMapper;
import com.hmdp.utils.UserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Collections;

@Service
public class ChatMemoryService {
    private static final Logger log = LoggerFactory.getLogger(ChatMemoryService.class);
    private static final String KEY_PREFIX = "ai:chat:memory:v2:";
    private static final int MAX_MESSAGES = 8;
    private static final long TTL_MINUTES = 30L;

    @Resource private StringRedisTemplate stringRedisTemplate;
    @Resource private ObjectMapper objectMapper;
    @Resource private AiConversationEventMapper eventMapper;

    public String resolveChatId(String chatId) {
        if (chatId != null && chatId.matches("[A-Za-z0-9-]{1,64}")) return chatId;
        return UUID.randomUUID().toString();
    }

    public List<Map<String, Object>> load(String chatId) {
        try {
            List<String> values = stringRedisTemplate.opsForList().range(key(chatId), 0, -1);
            if (values != null && !values.isEmpty()) return decodeMessages(values);
            return restoreFromDatabase(chatId);
        } catch (Exception e) {
            log.warn("[AI][chat] event=MEMORY_LOAD_FAILURE chatId={} errorType={}", chatId, e.getClass().getSimpleName());
            return restoreFromDatabase(chatId);
        }
    }

    public void appendTurn(String chatId, String userMessage, String assistantMessage, String route, Long decisionSessionId) {
        try {
            String user = objectMapper.writeValueAsString(message("user", userMessage));
            String assistant = objectMapper.writeValueAsString(message("assistant", assistantMessage));
            stringRedisTemplate.execute(appendScript(), Collections.singletonList(key(chatId)), user, assistant,
                    String.valueOf(MAX_MESSAGES), String.valueOf(TTL_MINUTES * 60));
        } catch (Exception e) {
            log.warn("[AI][chat] event=MEMORY_CACHE_FAILURE chatId={} errorType={}", chatId, e.getClass().getSimpleName());
        }
    }

    public Long findLatestDecisionSessionId(String chatId) {
        try {
            AiConversationEvent record = eventMapper.selectOne(new QueryWrapper<AiConversationEvent>()
                    .eq("chat_id", chatId).eq("event_type", "ASSISTANT_OUTPUT")
                    .apply("JSON_EXTRACT(event_result, '$.decisionSessionId') IS NOT NULL")
                    .orderByDesc("id").last("limit 1"));
            if (record == null || record.getEventResult() == null) return null;
            return objectMapper.readTree(record.getEventResult()).path("decisionSessionId").isNumber()
                    ? objectMapper.readTree(record.getEventResult()).path("decisionSessionId").asLong() : null;
        } catch (Exception e) {
            log.warn("[AI][chat] event=DECISION_CONTEXT_LOOKUP_FAILURE chatId={} errorType={}", chatId,
                    e.getClass().getSimpleName());
            return null;
        }
    }

    private List<Map<String, Object>> restoreFromDatabase(String chatId) {
        try {
            List<AiConversationEvent> records = eventMapper.selectList(new QueryWrapper<AiConversationEvent>()
                    .eq("chat_id", chatId).in("event_type", "USER_INPUT", "ASSISTANT_OUTPUT")
                    .eq("status", "SUCCESS").orderByDesc("id").last("limit " + MAX_MESSAGES));
            List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
            for (int i = records.size() - 1; i >= 0; i--) {
                AiConversationEvent event = records.get(i);
                String role = "USER_INPUT".equals(event.getEventType()) ? "user" : "assistant";
                String content = objectMapper.readTree(event.getEventResult()).path("content").asText("");
                result.add(message(role, content));
            }
            if (!result.isEmpty()) log.info("[AI][chat] event=MEMORY_RESTORED chatId={} messages={}", chatId, result.size());
            return result;
        } catch (Exception e) {
            log.warn("[AI][chat] event=MEMORY_DATABASE_FAILURE chatId={} errorType={}", chatId, e.getClass().getSimpleName());
            return new ArrayList<Map<String, Object>>();
        }
    }

    private List<Map<String, Object>> decodeMessages(List<String> values) throws Exception {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (String value : values) result.add(messageMap(objectMapper.readValue(value, new TypeReference<Map<String, Object>>() { })));
        return result;
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("role", normalizeRole(role));
        String value = content == null ? "" : content.replaceAll("[\\r\\n\\t]+", " ");
        item.put("content", value.length() > 600 ? value.substring(0, 600) + "..." : value);
        return item;
    }

    private String key(String chatId) {
        return KEY_PREFIX + chatId;
    }

    private Map<String, Object> messageMap(Map<String, Object> item) {
        return message(String.valueOf(item.get("role")), String.valueOf(item.get("content")));
    }

    private String normalizeRole(String role) {
        if (role == null) return "user";
        if ("USER".equalsIgnoreCase(role)) return "user";
        if ("ASSISTANT".equalsIgnoreCase(role)) return "assistant";
        return role.toLowerCase();
    }

    private DefaultRedisScript<Long> appendScript() {
        return new DefaultRedisScript<Long>(
                "redis.call('RPUSH', KEYS[1], ARGV[1], ARGV[2]); "
                        + "redis.call('LTRIM', KEYS[1], -tonumber(ARGV[3]), -1); "
                        + "redis.call('EXPIRE', KEYS[1], tonumber(ARGV[4])); return 1;", Long.class);
    }
}
