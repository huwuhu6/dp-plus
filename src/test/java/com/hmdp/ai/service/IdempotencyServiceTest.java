package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.entity.AiIdempotencyRecord;
import com.hmdp.ai.mapper.AiIdempotencyRecordMapper;
import com.hmdp.ai.runtime.IdempotencyScope;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IdempotencyServiceTest {
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void persistsFirstCommandAndResultOnce() {
        AiIdempotencyRecordMapper mapper = mock(AiIdempotencyRecordMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        AtomicInteger calls = new AtomicInteger();

        DecisionResponse result = service(mapper).execute(IdempotencyScope.DECISION_START, "start-1", "payload",
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

        DecisionResponse result = service(mapper).execute(IdempotencyScope.DECISION_FOLLOW_UP, "follow-1", "same",
                DecisionResponse.class, () -> response(calls.incrementAndGet()));

        assertEquals(42L, result.getSessionId());
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsSameKeyWithDifferentPayload() throws Exception {
        AiIdempotencyRecordMapper mapper = mock(AiIdempotencyRecordMapper.class);
        when(mapper.selectOne(any())).thenReturn(record("old", objectMapper.writeValueAsString(response(1))));

        assertThrows(IdempotencyKeyConflictException.class, () -> service(mapper).execute(
                IdempotencyScope.RESTORE, "restore-1", "new", DecisionResponse.class, () -> response(2)));
    }

    @Test
    void identifiesAProcessingDuplicateSeparatelyFromAHit() {
        AiIdempotencyRecordMapper mapper = mock(AiIdempotencyRecordMapper.class);
        AiIdempotencyRecord processing = new AiIdempotencyRecord();
        processing.setRequestHash(hash("same")); processing.setStatus("PROCESSING");
        when(mapper.selectOne(any())).thenReturn(processing);

        assertThrows(IdempotencyInProgressException.class, () -> service(mapper).execute(
                IdempotencyScope.CHAT_MESSAGE, "chat-1", "same", DecisionResponse.class, () -> response(1)));
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
