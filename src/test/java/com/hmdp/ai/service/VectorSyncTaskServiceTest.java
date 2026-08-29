package com.hmdp.ai.service;

import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.entity.AiVectorSyncTask;
import com.hmdp.ai.mapper.AiVectorSyncTaskMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorSyncTaskServiceTest {
    private VectorSyncTaskService service;
    private AiVectorSyncTaskMapper mapper;

    @BeforeEach
    void setUp() {
        service = new VectorSyncTaskService();
        mapper = mock(AiVectorSyncTaskMapper.class);
        ReflectionTestUtils.setField(service, "taskMapper", mapper);
        ReflectionTestUtils.setField(service, "aiProperties", new AiProperties());
    }

    @Test
    void staleWorkerCannotMarkNewerRevisionSynced() {
        AiVectorSyncTask task = task("shop-review-31", "UPSERT", 10L, "lease-a");
        when(mapper.markSynced("shop-review-31", "UPSERT", 10L, "lease-a")).thenReturn(0);

        service.markApplied(task);

        verify(mapper).requeueNewerState(eq("shop-review-31"), eq("UPSERT"), eq(10L), eq("lease-a"), any());
    }

    @Test
    void failedMatchingTaskUsesConditionalRetry() {
        AiVectorSyncTask task = task("shop-profile-7", "UPSERT", 11L, "lease-b");
        when(mapper.retryClaim(eq("shop-profile-7"), eq("UPSERT"), eq(11L), eq("lease-b"), any(), any())).thenReturn(1);

        service.markFailed(task, new IllegalStateException("milvus timeout"));

        verify(mapper).retryClaim(eq("shop-profile-7"), eq("UPSERT"), eq(11L), eq("lease-b"), any(), any());
    }

    private AiVectorSyncTask task(String id, String operation, Long revision, String token) {
        AiVectorSyncTask task = new AiVectorSyncTask();
        task.setDocumentId(id);
        task.setOperation(operation);
        task.setTargetRevision(revision);
        task.setLeaseToken(token);
        task.setAttemptCount(1);
        return task;
    }
}
