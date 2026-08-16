package com.hmdp.ai.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class ChatMemoryService {
    private static final String KEY_PREFIX = "ai:chat:memory:";
    private static final int MAX_MESSAGES = 8;
    private static final long TTL_MINUTES = 30L;

    @Resource private StringRedisTemplate stringRedisTemplate;
    @Resource private ObjectMapper objectMapper;

    public String resolveChatId(String chatId) {
        if (chatId != null && chatId.matches("[A-Za-z0-9-]{1,64}")) return chatId;
        return UUID.randomUUID().toString();
    }

    public List<Map<String, Object>> load(String chatId) {
        try {
            String value = stringRedisTemplate.opsForValue().get(key(chatId));
            if (value == null || value.trim().isEmpty()) return new ArrayList<Map<String, Object>>();
            return objectMapper.readValue(value, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            return new ArrayList<Map<String, Object>>();
        }
    }

    public void appendTurn(String chatId, String userMessage, String assistantMessage) {
        try {
            List<Map<String, Object>> messages = load(chatId);
            messages.add(message("user", userMessage));
            messages.add(message("assistant", assistantMessage));
            if (messages.size() > MAX_MESSAGES) {
                messages = new ArrayList<Map<String, Object>>(messages.subList(messages.size() - MAX_MESSAGES, messages.size()));
            }
            stringRedisTemplate.opsForValue().set(key(chatId), objectMapper.writeValueAsString(messages), TTL_MINUTES, TimeUnit.MINUTES);
        } catch (Exception ignored) {
            // Chat memory is an enhancement and must not block a user response.
        }
    }

    private Map<String, Object> message(String role, String content) {
        Map<String, Object> item = new LinkedHashMap<String, Object>();
        item.put("role", role);
        String value = content == null ? "" : content.replaceAll("[\\r\\n\\t]+", " ");
        item.put("content", value.length() > 600 ? value.substring(0, 600) + "..." : value);
        return item;
    }

    private String key(String chatId) {
        return KEY_PREFIX + chatId;
    }
}
