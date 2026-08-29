package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.entity.AiReviewDocument;
import com.hmdp.ai.entity.AiVectorSyncTask;
import com.hmdp.ai.mapper.AiVectorSyncTaskMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/** Persists the latest desired vector state; it never performs network I/O. */
@Service
public class VectorSyncTaskService {
    public static final String UPSERT = "UPSERT";
    public static final String DELETE = "DELETE";
    public static final String PENDING = "PENDING";
    public static final String SYNCING = "SYNCING";
    public static final String SYNCED = "SYNCED";

    @Resource private AiVectorSyncTaskMapper taskMapper;
    @Resource private AiProperties aiProperties;

    public void enqueueReviewUpsert(AiReviewDocument document) {
        merge(task(document.getId(), document.getShopId(), SemanticShopDocumentFactory.REVIEW, UPSERT,
                value(document.getSourceRevision())));
    }

    public void enqueueReviewDelete(AiReviewDocument document, Long sourceRevision) {
        merge(task(document.getId(), document.getShopId(), SemanticShopDocumentFactory.REVIEW, DELETE,
                value(sourceRevision)));
    }

    public void enqueueProfileUpsert(Long shopId, Long aggregatedRevision) {
        merge(task(shopId, shopId, SemanticShopDocumentFactory.PROFILE, UPSERT, value(aggregatedRevision)));
    }

    /** Requeues an already-synced desired state only after reconciliation has observed actual Milvus drift. */
    public void requestProfileRepair(Long shopId, long revision) {
        requestRepair(task(shopId, shopId, SemanticShopDocumentFactory.PROFILE, UPSERT, revision));
    }

    public void requestReviewRepair(Long reviewDocumentId, Long shopId, long revision) {
        requestRepair(task(reviewDocumentId, shopId, SemanticShopDocumentFactory.REVIEW, UPSERT, revision));
    }

    public void requestManagedDeleteRepair(String documentId, String documentType, Long entityId, Long shopId, long revision) {
        AiVectorSyncTask task = task(entityId, shopId, documentType, DELETE, revision);
        task.setDocumentId(documentId);
        requestRepair(task);
    }

    public List<AiVectorSyncTask> dueTasks(int requestedBatchSize) {
        int batchSize = Math.max(1, Math.min(requestedBatchSize, 100));
        return taskMapper.selectList(new QueryWrapper<AiVectorSyncTask>()
                .eq("status", PENDING).le("available_at", LocalDateTime.now())
                .orderByAsc("available_at").orderByAsc("id").last("limit " + batchSize));
    }

    public boolean claim(AiVectorSyncTask task) {
        String token = UUID.randomUUID().toString();
        LocalDateTime now = LocalDateTime.now();
        int updated = taskMapper.claim(task.getId(), task.getOperation(), task.getTargetRevision(), token, now);
        if (updated == 1) {
            task.setStatus(SYNCING);
            task.setLeaseToken(token);
            task.setLeasedAt(now);
            return true;
        }
        return false;
    }

    public boolean markApplied(AiVectorSyncTask task) {
        int updated = taskMapper.markSynced(task.getDocumentId(), task.getOperation(), task.getTargetRevision(), task.getLeaseToken());
        if (updated == 1) return true;
        requeueNewerState(task);
        return false;
    }

    public void markFailed(AiVectorSyncTask task, Exception error) {
        LocalDateTime retryAt = LocalDateTime.now().plusSeconds(retryDelaySeconds(task));
        int updated = taskMapper.retryClaim(task.getDocumentId(), task.getOperation(), task.getTargetRevision(),
                task.getLeaseToken(), retryAt, compact(error));
        if (updated == 0) requeueNewerState(task);
    }

    public int recoverExpiredLeases() {
        LocalDateTime now = LocalDateTime.now();
        int leaseSeconds = Math.max(15, value(aiProperties.getVectorSync().getLeaseSeconds(), 90));
        return taskMapper.recoverExpiredLeases(now.minusSeconds(leaseSeconds), now);
    }

    private void merge(AiVectorSyncTask task) {
        // MySQL returns 1 for insert, 2 for a changed duplicate-key update, and 0 for an idempotent duplicate.
        // All three outcomes mean that this document's desired state is durably represented.
        taskMapper.mergeLatest(task);
    }

    private void requestRepair(AiVectorSyncTask task) {
        merge(task);
        taskMapper.requeueSyncedDesired(task.getDocumentId(), task.getOperation(), task.getTargetRevision());
    }

    private AiVectorSyncTask task(Long entityId, Long shopId, String type, String operation, long revision) {
        AiVectorSyncTask task = new AiVectorSyncTask();
        task.setDocumentType(type);
        task.setEntityId(entityId);
        task.setShopId(shopId);
        task.setOperation(operation);
        task.setTargetRevision(revision);
        task.setDocumentId(SemanticShopDocumentFactory.PROFILE.equals(type)
                ? "shop-profile-" + shopId : "shop-review-" + entityId);
        return task;
    }

    private void requeueNewerState(AiVectorSyncTask task) {
        taskMapper.requeueNewerState(task.getDocumentId(), task.getOperation(), task.getTargetRevision(),
                task.getLeaseToken(), LocalDateTime.now());
    }

    private int retryDelaySeconds(AiVectorSyncTask task) {
        int base = Math.max(1, value(aiProperties.getVectorSync().getRetryDelaySeconds(), 15));
        int attempts = task.getAttemptCount() == null ? 1 : task.getAttemptCount();
        return Math.min(base * (1 << Math.min(4, Math.max(0, attempts - 1))), 300);
    }

    private String compact(Exception error) {
        String value = error == null ? "unknown" : error.getClass().getSimpleName() + ": " + error.getMessage();
        return value == null ? "unknown" : value.substring(0, Math.min(value.length(), 512));
    }

    private long value(Long value) { return value == null ? 0L : value; }
    private int value(Integer value, int fallback) { return value == null ? fallback : value; }
}
