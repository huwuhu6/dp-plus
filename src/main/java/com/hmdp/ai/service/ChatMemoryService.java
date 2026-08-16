package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.entity.AiChatMessage;
import com.hmdp.ai.mapper.AiChatMessageMapper;
import com.hmdp.utils.UserHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import jakarta.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class ChatMemoryService {
    private static final Logger log = LoggerFactory.getLogger(ChatMemoryService.class);
    private static final String KEY_PREFIX = "ai:chat:memory:";
    private static final int MAX_MESSAGES = 8;
    private static final long TTL_MINUTES = 30L;

    @Resource private StringRedisTemplate stringRedisTemplate;
    @Resource private ObjectMapper objectMapper;
    @Resource private AiChatMessageMapper chatMessageMapper;

    public String resolveChatId(String chatId) {
        if (chatId != null && chatId.matches("[A-Za-z0-9-]{1,64}")) return chatId;
        return UUID.randomUUID().toString();
    }

    public List<Map<String, Object>> load(String chatId) {
        try {
            String value = stringRedisTemplate.opsForValue().get(key(chatId));
            if (value != null && !value.trim().isEmpty()) {
                return normalizeMessages(objectMapper.readValue(value, new TypeReference<List<Map<String, Object>>>() { }));
            }
            List<Map<String, Object>> restored = restoreFromDatabase(chatId);
            if (!restored.isEmpty()) cache(chatId, restored);
            return restored;
        } catch (Exception e) {
            log.warn("[AI][chat] event=MEMORY_LOAD_FAILURE chatId={} errorType={}", chatId, e.getClass().getSimpleName());
            return restoreFromDatabase(chatId);
        }
    }

    public void appendTurn(String chatId, String userMessage, String assistantMessage, String route, Long decisionSessionId) {
        saveMessage(chatId, "USER", userMessage, route, decisionSessionId);
        saveMessage(chatId, "ASSISTANT", assistantMessage, route, decisionSessionId);
        try {
            List<Map<String, Object>> messages = load(chatId);
            messages.add(message("user", userMessage));
            messages.add(message("assistant", assistantMessage));
            if (messages.size() > MAX_MESSAGES) {
                messages = new ArrayList<Map<String, Object>>(messages.subList(messages.size() - MAX_MESSAGES, messages.size()));
            }
            cache(chatId, messages);
        } catch (Exception e) {
            log.warn("[AI][chat] event=MEMORY_CACHE_FAILURE chatId={} errorType={}", chatId, e.getClass().getSimpleName());
        }
    }

    public Long findLatestDecisionSessionId(String chatId) {
        try {
            AiChatMessage record = chatMessageMapper.selectOne(new QueryWrapper<AiChatMessage>()
                    .eq("chat_id", chatId).isNotNull("decision_session_id").orderByDesc("id").last("limit 1"));
            return record == null ? null : record.getDecisionSessionId();
        } catch (Exception e) {
            log.warn("[AI][chat] event=DECISION_CONTEXT_LOOKUP_FAILURE chatId={} errorType={}", chatId,
                    e.getClass().getSimpleName());
            return null;
        }
    }

    private List<Map<String, Object>> restoreFromDatabase(String chatId) {
        try {
            List<AiChatMessage> records = chatMessageMapper.selectList(new QueryWrapper<AiChatMessage>()
                    .eq("chat_id", chatId).orderByDesc("id").last("limit " + MAX_MESSAGES));
            List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
            for (int i = records.size() - 1; i >= 0; i--) result.add(message(records.get(i).getRole(), records.get(i).getContent()));
            if (!result.isEmpty()) log.info("[AI][chat] event=MEMORY_RESTORED chatId={} messages={}", chatId, result.size());
            return result;
        } catch (Exception e) {
            log.warn("[AI][chat] event=MEMORY_DATABASE_FAILURE chatId={} errorType={}", chatId, e.getClass().getSimpleName());
            return new ArrayList<Map<String, Object>>();
        }
    }

    private void saveMessage(String chatId, String role, String content, String route, Long decisionSessionId) {
        AiChatMessage record = new AiChatMessage();
        record.setChatId(chatId);
        record.setUserId(UserHolder.getUser() == null ? null : UserHolder.getUser().getId());
        record.setDecisionSessionId(decisionSessionId);
        record.setRole(normalizeRole(role));
        record.setRoute(route);
        record.setContent(content == null ? "" : content);
        chatMessageMapper.insert(record);
    }

    private void cache(String chatId, List<Map<String, Object>> messages) throws Exception {
        stringRedisTemplate.opsForValue().set(key(chatId), objectMapper.writeValueAsString(messages), TTL_MINUTES, TimeUnit.MINUTES);
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

    private List<Map<String, Object>> normalizeMessages(List<Map<String, Object>> messages) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> item : messages) {
            result.add(message(String.valueOf(item.get("role")), String.valueOf(item.get("content"))));
        }
        return result;
    }

    private String normalizeRole(String role) {
        if (role == null) return "user";
        if ("USER".equalsIgnoreCase(role)) return "user";
        if ("ASSISTANT".equalsIgnoreCase(role)) return "assistant";
        return role.toLowerCase();
    }
}
