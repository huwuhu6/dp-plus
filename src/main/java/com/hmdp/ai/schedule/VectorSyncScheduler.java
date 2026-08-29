package com.hmdp.ai.schedule;

import com.hmdp.ai.service.VectorSyncWorker;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Disabled by default so existing deployments keep their current full-rebuild-only behavior. */
@Component
@ConditionalOnProperty(prefix = "ai.vector-sync", name = "enabled", havingValue = "true")
public class VectorSyncScheduler {
    @Resource private VectorSyncWorker vectorSyncWorker;

    @Scheduled(fixedDelayString = "${ai.vector-sync.fixed-delay-ms:15000}")
    public void syncPendingTasks() {
        vectorSyncWorker.syncPendingTasks();
    }
}
