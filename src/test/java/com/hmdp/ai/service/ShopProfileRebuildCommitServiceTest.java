package com.hmdp.ai.service;

import com.hmdp.ai.mapper.AiShopProfileMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopProfileRebuildCommitServiceTest {
    private ShopProfileRebuildCommitService service;
    private AiShopProfileMapper profileMapper;
    private VectorSyncTaskService taskService;

    @BeforeEach
    void setUp() {
        service = new ShopProfileRebuildCommitService();
        profileMapper = mock(AiShopProfileMapper.class);
        taskService = mock(VectorSyncTaskService.class);
        ReflectionTestUtils.setField(service, "profileMapper", profileMapper);
        ReflectionTestUtils.setField(service, "taskService", taskService);
    }

    @Test
    void enqueuesProfileOnlyAfterMatchingCasSucceeds() {
        when(profileMapper.completeRebuild(7L, 10L, "聚餐", "安静", "LOW", "摘要")).thenReturn(1);

        assertTrue(service.completeRebuild(7L, 10L, "聚餐", "安静", "LOW", "摘要"));

        verify(taskService).enqueueProfileUpsert(7L, 10L);
    }

    @Test
    void doesNotEnqueueWhenProfileCasIsStale() {
        when(profileMapper.completeWithoutReviews(7L, 10L)).thenReturn(0);

        assertFalse(service.completeWithoutReviews(7L, 10L));

        verify(taskService, never()).enqueueProfileUpsert(7L, 10L);
    }
}
