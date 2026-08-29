package com.hmdp.ai.service;

import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.entity.AiVectorSyncTask;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/** Claims a durable task before network I/O and conditionally records the matching result afterwards. */
@Service
public class VectorSyncWorker {
    private static final Logger log = LoggerFactory.getLogger(VectorSyncWorker.class);
    @Resource private VectorSyncTaskService taskService;
    @Resource private SemanticShopIndexWriter indexWriter;
    @Resource private AiProperties aiProperties;

    public int syncPendingTasks() {
        taskService.recoverExpiredLeases();
        int batchSize = aiProperties.getVectorSync().getBatchSize() == null ? 20 : aiProperties.getVectorSync().getBatchSize();
        List<AiVectorSyncTask> tasks = taskService.dueTasks(batchSize);
        int applied = 0;
        for (AiVectorSyncTask task : tasks) {
            if (!taskService.claim(task)) continue;
            try {
                indexWriter.apply(task);
                if (taskService.markApplied(task)) applied++;
            } catch (Exception e) {
                taskService.markFailed(task, e);
                log.warn("[AI][vector-sync] event=SYNC_FAILED documentId={} operation={} revision={} errorType={}",
                        task.getDocumentId(), task.getOperation(), task.getTargetRevision(), e.getClass().getSimpleName());
            }
        }
        return applied;
    }
}
