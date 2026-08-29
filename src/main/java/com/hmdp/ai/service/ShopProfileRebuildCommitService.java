package com.hmdp.ai.service;

import com.hmdp.ai.mapper.AiShopProfileMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keeps the successful Profile CAS and its vector-sync intent in one short MySQL transaction. */
@Service
public class ShopProfileRebuildCommitService {
    @Resource private AiShopProfileMapper profileMapper;
    @Resource private VectorSyncTaskService taskService;

    @Transactional
    public boolean completeRebuild(Long shopId, Long expectedRevision, String sceneTags, String ambienceTags,
                                   String queueLevel, String summary) {
        if (profileMapper.completeRebuild(shopId, expectedRevision, sceneTags, ambienceTags, queueLevel, summary) != 1) {
            return false;
        }
        taskService.enqueueProfileUpsert(shopId, expectedRevision);
        return true;
    }

    @Transactional
    public boolean completeWithoutReviews(Long shopId, Long expectedRevision) {
        if (profileMapper.completeWithoutReviews(shopId, expectedRevision) != 1) return false;
        taskService.enqueueProfileUpsert(shopId, expectedRevision);
        return true;
    }
}
