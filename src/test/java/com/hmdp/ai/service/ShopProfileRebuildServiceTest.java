package com.hmdp.ai.service;

import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.entity.AiShopProfile;
import com.hmdp.ai.entity.ShopReview;
import com.hmdp.ai.mapper.AiShopProfileMapper;
import com.hmdp.ai.mapper.ShopReviewMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopProfileRebuildServiceTest {
    private ShopProfileRebuildService service;
    private AiShopProfileMapper profileMapper;
    private ShopReviewMapper reviewMapper;
    private ShopProfileDraftProvider draftGenerator;
    private ShopProfileRebuildCommitService rebuildCommitService;

    @BeforeEach
    void setUp() {
        service = new ShopProfileRebuildService();
        profileMapper = mock(AiShopProfileMapper.class);
        reviewMapper = mock(ShopReviewMapper.class);
        draftGenerator = mock(ShopProfileDraftProvider.class);
        rebuildCommitService = mock(ShopProfileRebuildCommitService.class);
        AiProperties properties = new AiProperties();
        properties.getProfileRebuild().setBatchSize(20);
        ReflectionTestUtils.setField(service, "profileMapper", profileMapper);
        ReflectionTestUtils.setField(service, "reviewMapper", reviewMapper);
        ReflectionTestUtils.setField(service, "draftGenerator", draftGenerator);
        ReflectionTestUtils.setField(service, "rebuildCommitService", rebuildCommitService);
        ReflectionTestUtils.setField(service, "aiProperties", properties);
    }

    @Test
    void completesOnlyWhenExpectedInputRevisionStillMatches() {
        AiShopProfile profile = pending(7L, 4L);
        when(profileMapper.selectOne(any())).thenReturn(profile);
        when(reviewMapper.selectList(any())).thenReturn(List.of(review("排队半小时"), review("周末排队")));
        when(draftGenerator.generate(eq(profile), any())).thenReturn(new ShopProfileDraftGenerator.ProfileDraft(
                List.of("朋友聚餐"), List.of("热闹"), "高峰期等位较多。"));
        when(rebuildCommitService.completeRebuild(7L, 4L, "朋友聚餐", "热闹", "HIGH", "高峰期等位较多。")).thenReturn(true);

        assertTrue(service.rebuild(7L));
        verify(rebuildCommitService).completeRebuild(7L, 4L, "朋友聚餐", "热闹", "HIGH", "高峰期等位较多。");
    }

    @Test
    void rejectsStaleResultInsteadOfOverwritingNewReviewInput() {
        AiShopProfile profile = pending(7L, 4L);
        when(profileMapper.selectOne(any())).thenReturn(profile);
        when(reviewMapper.selectList(any())).thenReturn(List.of(review("环境安静")));
        when(draftGenerator.generate(eq(profile), any())).thenReturn(new ShopProfileDraftGenerator.ProfileDraft(
                List.of("约会"), List.of("安静"), "适合聊天。"));
        when(rebuildCommitService.completeRebuild(any(), any(), any(), any(), any(), any())).thenReturn(false);

        assertFalse(service.rebuild(7L));
    }

    @Test
    void preservesExistingProfileWhenNoActiveReviewsExist() {
        AiShopProfile profile = pending(7L, 4L);
        when(profileMapper.selectOne(any())).thenReturn(profile);
        when(reviewMapper.selectList(any())).thenReturn(List.of());
        when(rebuildCommitService.completeWithoutReviews(7L, 4L)).thenReturn(true);

        assertTrue(service.rebuild(7L));
        verify(draftGenerator, never()).generate(any(), any());
        verify(rebuildCommitService).completeWithoutReviews(7L, 4L);
    }

    @Test
    void leavesProfileWaitingWhenModelOutputIsInvalidOrTimesOut() {
        AiShopProfile profile = pending(7L, 4L);
        when(profileMapper.selectOne(any())).thenReturn(profile);
        when(reviewMapper.selectList(any())).thenReturn(List.of(review("环境安静")));
        when(draftGenerator.generate(eq(profile), any())).thenThrow(new IllegalArgumentException("invalid output"));

        assertFalse(service.rebuild(7L));
        verify(rebuildCommitService, never()).completeRebuild(any(), any(), any(), any(), any(), any());
    }

    @Test
    void scansOnlyPendingProfilesInConfiguredBatches() {
        AiShopProfile profile = pending(7L, 4L);
        when(profileMapper.selectList(any())).thenReturn(List.of(profile));
        when(profileMapper.selectOne(any())).thenReturn(profile);
        when(reviewMapper.selectList(any())).thenReturn(List.of());
        when(rebuildCommitService.completeWithoutReviews(7L, 4L)).thenReturn(true);

        assertEquals(1, service.rebuildPendingProfiles(1));
    }

    private AiShopProfile pending(Long shopId, Long revision) {
        AiShopProfile profile = new AiShopProfile();
        profile.setShopId(shopId);
        profile.setCuisine("日料");
        profile.setInputRevision(revision);
        profile.setProfileStatus("WAIT_REBUILD");
        return profile;
    }

    private ShopReview review(String content) {
        ShopReview review = new ShopReview();
        review.setContent(content);
        review.setRating(4);
        return review;
    }
}
