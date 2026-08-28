package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.entity.AiIdempotencyRecord;
import com.hmdp.ai.mapper.AiIdempotencyRecordMapper;
import com.hmdp.ai.runtime.IdempotencyScope;
import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @AfterEach
    void clearUser() {
        UserHolder.removeUser();
    }

    @Test
    void persistsFirstCommandAndResultOnce() {
        AiIdempotencyRecordMapper mapper = mock(AiIdempotencyRecordMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        AtomicInteger calls = new AtomicInteger();

        DecisionResponse result = service(mapper).execute(IdempotencyScope.DECISION_START, null, "start-1", "payload",
                DecisionResponse.class, () -> response(calls.incrementAndGet()));

        assertEquals(1L, result.getSessionId());
        assertEquals(1, calls.get());
        verify(mapper).insert(any(AiIdempotencyRecord.class));
        verify(mapper).updateById(any(AiIdempotencyRecord.class));
    }

    @Test
    void replaysSucceededCommandWithoutReexecution() throws Exception {
        AiIdempotencyRecordMapper mapper = mock(AiIdempotencyRecordMapper.class);
        AiIdempotencyRecord record = record("same", objectMapper.writeValueAsString(response(42)));
        when(mapper.selectOne(any())).thenReturn(record);
        AtomicInteger calls = new AtomicInteger();

        DecisionResponse result = service(mapper).execute(IdempotencyScope.DECISION_FOLLOW_UP, "chat-1", "follow-1", "same",
                DecisionResponse.class, () -> response(calls.incrementAndGet()));

        assertEquals(42L, result.getSessionId());
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsSameKeyWithDifferentPayload() throws Exception {
        AiIdempotencyRecordMapper mapper = mock(AiIdempotencyRecordMapper.class);
        when(mapper.selectOne(any())).thenReturn(record("old", objectMapper.writeValueAsString(response(1))));

        assertThrows(IdempotencyKeyConflictException.class, () -> service(mapper).execute(
                IdempotencyScope.RESTORE, "restore-chat", "restore-1", "new", DecisionResponse.class, () -> response(2)));
    }

    @Test
    void identifiesAProcessingDuplicateSeparatelyFromAHit() {
        AiIdempotencyRecordMapper mapper = mock(AiIdempotencyRecordMapper.class);
        AiIdempotencyRecord processing = new AiIdempotencyRecord();
        processing.setRequestHash(hash("same")); processing.setStatus("PROCESSING");
        when(mapper.selectOne(any())).thenReturn(processing);

        assertThrows(IdempotencyInProgressException.class, () -> service(mapper).execute(
                IdempotencyScope.CHAT_MESSAGE, "chat-1", "chat-message-1", "same", DecisionResponse.class, () -> response(1)));
    }

    @Test
    void scopesAKeyByUserAndChatBeforeReplaying() throws Exception {
        AiIdempotencyRecordMapper mapper = mock(AiIdempotencyRecordMapper.class);
        AiIdempotencyRecord firstRecord = record("same", objectMapper.writeValueAsString(response(1)));
        List<com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?>> lookups = new ArrayList<>();
        AtomicInteger lookupCount = new AtomicInteger();
        when(mapper.selectOne(any())).thenAnswer(invocation -> {
            com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?> query = invocation.getArgument(0);
            lookups.add(query);
            int lookup = lookupCount.incrementAndGet();
            return (lookup == 2 || lookup == 3) ? firstRecord : null;
        });
        IdempotencyService service = service(mapper);
        AtomicInteger calls = new AtomicInteger();

        asUser(1L);
        DecisionResponse first = service.execute(IdempotencyScope.CHAT_MESSAGE, "chat-A", "K", "same",
                DecisionResponse.class, () -> response(calls.incrementAndGet()));
        DecisionResponse replay = service.execute(IdempotencyScope.CHAT_MESSAGE, "chat-A", "K", "same",
                DecisionResponse.class, () -> response(calls.incrementAndGet()));

        assertEquals(first.getSessionId(), replay.getSessionId());
        assertEquals(1, calls.get(), "same user, chat, key and request must replay");
        assertThrows(IdempotencyKeyConflictException.class, () -> service.execute(
                IdempotencyScope.CHAT_MESSAGE, "chat-A", "K", "different", DecisionResponse.class,
                () -> response(calls.incrementAndGet())));

        DecisionResponse otherChat = service.execute(IdempotencyScope.CHAT_MESSAGE, "chat-B", "K", "same",
                DecisionResponse.class, () -> response(calls.incrementAndGet()));
        assertEquals(2L, otherChat.getSessionId(), "the same key in another chat is a new command");

        asUser(2L);
        DecisionResponse otherUser = service.execute(IdempotencyScope.CHAT_MESSAGE, "chat-A", "K", "same",
                DecisionResponse.class, () -> response(calls.incrementAndGet()));
        assertEquals(3L, otherUser.getSessionId(), "the same chat and key from another user must not replay");
        assertEquals(3, calls.get());
        assertLookup(lookups.get(0), 1L, "chat-A", "K");
        assertLookup(lookups.get(1), 1L, "chat-A", "K");
        assertLookup(lookups.get(3), 1L, "chat-B", "K");
        assertLookup(lookups.get(4), 2L, "chat-A", "K");
    }

    @Test
    void rejectsMissingChatForConversationScopedCommand() {
        assertThrows(IllegalArgumentException.class, () -> service(mock(AiIdempotencyRecordMapper.class)).execute(
                IdempotencyScope.RESTORE, null, "restore-1", "request", DecisionResponse.class, () -> response(1)));
    }

    private IdempotencyService service(AiIdempotencyRecordMapper mapper) {
        IdempotencyService service = new IdempotencyService();
        ReflectionTestUtils.setField(service, "recordMapper", mapper);
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper);
        return service;
    }

    private AiIdempotencyRecord record(String payload, String resultJson) {
        AiIdempotencyRecord record = new AiIdempotencyRecord();
        record.setRequestHash(hash(payload)); record.setStatus("SUCCEEDED"); record.setResultJson(resultJson);
        return record;
    }

    private void asUser(long id) {
        UserDTO user = new UserDTO();
        user.setId(id);
        UserHolder.saveUser(user);
    }

    private void assertLookup(com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<?> query,
                              long userId, String chatId, String key) {
        query.getSqlSegment();
        Map<String, Object> values = query.getParamNameValuePairs();
        assertEquals(true, values.containsValue(userId));
        assertEquals(true, values.containsValue(chatId));
        assertEquals(true, values.containsValue(IdempotencyScope.CHAT_MESSAGE.name()));
        assertEquals(true, values.containsValue(key));
    }

    private String hash(String payload) {
        try {
            byte[] bytes = java.security.MessageDigest.getInstance("SHA-256").digest(objectMapper.writeValueAsBytes(payload));
            StringBuilder result = new StringBuilder();
            for (byte value : bytes) result.append(String.format("%02x", value));
            return result.toString();
        } catch (Exception e) { throw new AssertionError(e); }
    }

    private DecisionResponse response(long sessionId) {
        DecisionResponse response = new DecisionResponse(); response.setSessionId(sessionId); response.setStatus("COMPLETED");
        return response;
    }
}
