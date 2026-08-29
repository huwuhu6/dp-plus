package com.hmdp.ai.service;

import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.entity.AiVectorSyncTask;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VectorSyncWorkerTest {
    private VectorSyncWorker worker;
    private VectorSyncTaskService taskService;
    private SemanticShopIndexWriter indexWriter;

    @BeforeEach
    void setUp() {
        worker = new VectorSyncWorker();
        taskService = mock(VectorSyncTaskService.class);
        indexWriter = mock(SemanticShopIndexWriter.class);
        AiProperties properties = new AiProperties();
        properties.getVectorSync().setBatchSize(10);
        ReflectionTestUtils.setField(worker, "taskService", taskService);
        ReflectionTestUtils.setField(worker, "indexWriter", indexWriter);
        ReflectionTestUtils.setField(worker, "aiProperties", properties);
    }

    @Test
    void onlyMarksTheClaimedTaskAppliedAfterWriterSucceeds() {
        AiVectorSyncTask task = task();
        when(taskService.dueTasks(10)).thenReturn(List.of(task));
        when(taskService.claim(task)).thenReturn(true);
        when(taskService.markApplied(task)).thenReturn(true);

        assertEquals(1, worker.syncPendingTasks());

        verify(taskService).recoverExpiredLeases();
        verify(indexWriter).apply(task);
        verify(taskService).markApplied(task);
    }

    @Test
    void writerFailureLeavesTaskForConditionalRetry() {
        AiVectorSyncTask task = task();
        when(taskService.dueTasks(10)).thenReturn(List.of(task));
        when(taskService.claim(task)).thenReturn(true);
        doThrow(new IllegalStateException("timeout")).when(indexWriter).apply(task);

        assertEquals(0, worker.syncPendingTasks());

        verify(taskService).markFailed(any(), any());
    }

    private AiVectorSyncTask task() {
        AiVectorSyncTask task = new AiVectorSyncTask();
        task.setDocumentId("shop-review-31");
        task.setOperation("UPSERT");
        task.setTargetRevision(4L);
        return task;
    }
}
