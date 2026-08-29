package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.HmDianPingApplication;
import com.hmdp.ai.entity.AiVectorSyncTask;
import com.hmdp.ai.mapper.AiVectorSyncTaskMapper;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = HmDianPingApplication.class)
class VectorSyncTaskMapperIntegrationTest {
    @Resource private AiVectorSyncTaskMapper mapper;
    private String documentId;

    @AfterEach
    void cleanup() {
        if (documentId != null) mapper.delete(new QueryWrapper<AiVectorSyncTask>().eq("document_id", documentId));
    }

    @Test
    void deleteWinsSameRevisionAndOldUpsetClaimCannotBecomeSynced() {
        documentId = "test-vector-" + UUID.randomUUID();
        mapper.mergeLatest(task("UPSERT", 10L));
        AiVectorSyncTask original = load();
        LocalDateTime now = LocalDateTime.now();
        assertEquals(1, mapper.claim(original.getId(), "UPSERT", 10L, "lease-old", now));

        mapper.mergeLatest(task("DELETE", 10L));
        assertEquals(0, mapper.markSynced(documentId, "UPSERT", 10L, "lease-old"));
        assertEquals(1, mapper.requeueNewerState(documentId, "UPSERT", 10L, "lease-old", LocalDateTime.now()));

        AiVectorSyncTask latest = load();
        assertEquals("DELETE", latest.getOperation());
        assertEquals(10L, latest.getTargetRevision());
        assertEquals("PENDING", latest.getStatus());
    }

    @Test
    void higherRevisionReplacesPendingTaskTarget() {
        documentId = "test-vector-" + UUID.randomUUID();
        mapper.mergeLatest(task("UPSERT", 10L));
        mapper.mergeLatest(task("UPSERT", 11L));

        AiVectorSyncTask latest = load();
        assertEquals("UPSERT", latest.getOperation());
        assertEquals(11L, latest.getTargetRevision());
        assertEquals("PENDING", latest.getStatus());
    }

    private AiVectorSyncTask load() {
        AiVectorSyncTask task = mapper.selectOne(new QueryWrapper<AiVectorSyncTask>().eq("document_id", documentId));
        assertNotNull(task);
        return task;
    }

    private AiVectorSyncTask task(String operation, Long revision) {
        AiVectorSyncTask task = new AiVectorSyncTask();
        task.setDocumentId(documentId);
        task.setDocumentType("REVIEW");
        task.setEntityId(1L);
        task.setShopId(1L);
        task.setOperation(operation);
        task.setTargetRevision(revision);
        return task;
    }
}
