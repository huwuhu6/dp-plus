package com.hmdp.ai.schedule;

import com.hmdp.ai.service.ShopProfileRebuildService;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "ai.profile-rebuild", name = "enabled", havingValue = "true")
public class ShopProfileRebuildScheduler {
    @Resource private ShopProfileRebuildService rebuildService;

    @Scheduled(fixedDelayString = "${ai.profile-rebuild.fixed-delay-ms:60000}")
    public void rebuildPendingProfiles() {
        rebuildService.rebuildPendingProfiles();
    }
}
