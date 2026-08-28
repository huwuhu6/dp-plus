package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.entity.AiIdempotencyRecord;
import com.hmdp.ai.mapper.AiIdempotencyRecordMapper;
import com.hmdp.ai.runtime.IdempotencyScope;
import com.hmdp.utils.UserHolder;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.function.Supplier;

/**
 * Persists request deduplication with the command transaction. A missing key deliberately
 * preserves legacy behaviour; it is not presented as client-retry idempotency.
 */
@Service
public class IdempotencyService {
    @Resource private AiIdempotencyRecordMapper recordMapper;
    @Resource private ObjectMapper objectMapper;

    @Transactional
    public <T> T execute(IdempotencyScope scope, String key, Object request, Class<T> resultType, Supplier<T> command) {
        if (!hasText(key)) return command.get();
        String normalizedKey = key.trim();
        if (normalizedKey.length() > 128) throw new IllegalArgumentException("Idempotency-Key must not exceed 128 characters");
        String hash = requestHash(request);
        Long userId = UserHolder.getUser() == null ? 0L : UserHolder.getUser().getId();
        AiIdempotencyRecord existing = find(scope, normalizedKey, userId);
        if (existing != null) return replay(existing, scope, normalizedKey, hash, resultType);

        AiIdempotencyRecord created = new AiIdempotencyRecord();
        created.setUserId(userId); created.setScope(scope.name()); created.setIdempotencyKey(normalizedKey);
        created.setRequestHash(hash); created.setStatus("PROCESSING");
        created.setCreatedAt(LocalDateTime.now()); created.setUpdatedAt(created.getCreatedAt());
        try {
            recordMapper.insert(created);
        } catch (DuplicateKeyException duplicate) {
            AiIdempotencyRecord winner = find(scope, normalizedKey, userId);
            if (winner == null) throw duplicate;
            return replay(winner, scope, normalizedKey, hash, resultType);
        }

        T result = command.get();
        try {
            created.setStatus("SUCCEEDED"); created.setResultJson(objectMapper.writeValueAsString(result));
            created.setResultReference(resultReference(result)); created.setUpdatedAt(LocalDateTime.now());
            recordMapper.updateById(created);
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Idempotency result cannot be persisted", e);
        }
    }

    private AiIdempotencyRecord find(IdempotencyScope scope, String key, Long userId) {
        QueryWrapper<AiIdempotencyRecord> query = new QueryWrapper<AiIdempotencyRecord>()
                .eq("scope", scope.name()).eq("idempotency_key", key);
        query.eq("user_id", userId == null ? 0L : userId);
        return recordMapper.selectOne(query);
    }

    private <T> T replay(AiIdempotencyRecord record, IdempotencyScope scope, String key, String hash, Class<T> type) {
        if (!hash.equals(record.getRequestHash())) throw new IdempotencyKeyConflictException(scope.name(), key);
        if (!"SUCCEEDED".equals(record.getStatus()) || !hasText(record.getResultJson())) {
            throw new IdempotencyInProgressException(scope.name(), key);
        }
        try { return objectMapper.readValue(record.getResultJson(), type); }
        catch (Exception e) { throw new IllegalStateException("Idempotency result cannot be read", e); }
    }

    private String requestHash(Object request) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(request));
            StringBuilder result = new StringBuilder();
            for (byte value : digest) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception e) { throw new IllegalStateException("Idempotency request cannot be hashed", e); }
    }

    private String resultReference(Object result) {
        if (result == null) return null;
        try {
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.valueToTree(result);
            if (root.hasNonNull("sessionId")) return root.get("sessionId").asText();
            if (root.hasNonNull("newVersion")) return "working-memory:" + root.get("newVersion").asText();
            return null;
        } catch (Exception ignored) { return null; }
    }

    private boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
}
