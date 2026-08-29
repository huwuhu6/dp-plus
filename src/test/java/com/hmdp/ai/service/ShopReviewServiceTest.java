package com.hmdp.ai.service;

import com.hmdp.ai.dto.ShopReviewUpsertRequest;
import com.hmdp.ai.entity.AiReviewDocument;
import com.hmdp.ai.entity.ShopReview;
import com.hmdp.ai.mapper.AiReviewDocumentMapper;
import com.hmdp.ai.mapper.AiShopProfileMapper;
import com.hmdp.ai.mapper.ShopReviewMapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopReviewServiceTest {
    private ShopReviewService service;
    private ShopReviewMapper reviewMapper;
    private AiReviewDocumentMapper documentMapper;
    private AiShopProfileMapper profileMapper;
    private VectorSyncTaskService vectorSyncTaskService;
    private ShopMapper shopMapper;

    @BeforeEach
    void setUp() {
        service = new ShopReviewService();
        reviewMapper = mock(ShopReviewMapper.class);
        documentMapper = mock(AiReviewDocumentMapper.class);
        profileMapper = mock(AiShopProfileMapper.class);
        vectorSyncTaskService = mock(VectorSyncTaskService.class);
        shopMapper = mock(ShopMapper.class);
        ReflectionTestUtils.setField(service, "reviewMapper", reviewMapper);
        ReflectionTestUtils.setField(service, "documentMapper", documentMapper);
        ReflectionTestUtils.setField(service, "profileMapper", profileMapper);
        ReflectionTestUtils.setField(service, "vectorSyncTaskService", vectorSyncTaskService);
        ReflectionTestUtils.setField(service, "shopMapper", shopMapper);
        when(shopMapper.selectById(7L)).thenReturn(new Shop());
        when(profileMapper.markDirty(7L)).thenReturn(1);
    }

    @Test
    void createsCanonicalReviewProjectionAndMarksProfileDirty() {
        when(reviewMapper.selectOne(any())).thenReturn(null);
        when(documentMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            invocation.<ShopReview>getArgument(0).setId(31L);
            return 1;
        }).when(reviewMapper).insert(any(ShopReview.class));
        when(documentMapper.insert(any(AiReviewDocument.class))).thenReturn(1);

        ShopReview created = service.upsert(request("First review", 5));

        assertEquals(31L, created.getId());
        assertEquals(1L, created.getRevision());
        ArgumentCaptor<AiReviewDocument> document = ArgumentCaptor.forClass(AiReviewDocument.class);
        verify(documentMapper).insert(document.capture());
        assertEquals(31L, document.getValue().getSourceReviewId());
        assertEquals(1L, document.getValue().getSourceRevision());
        assertEquals("SHOP_REVIEW", document.getValue().getSourceType());
        assertEquals(1, document.getValue().getSentiment());
        verify(profileMapper).markDirty(7L);
    }

    @Test
    void replaysUnchangedActiveSourceWithoutDirtyingProfileAgain() {
        ShopReview existing = review(31L, 1L, "First review", 5, "ACTIVE");
        when(reviewMapper.selectOne(any())).thenReturn(existing);

        ShopReview result = service.upsert(request("First review", 5));

        assertEquals(existing, result);
        verify(profileMapper, never()).markDirty(any());
        verify(documentMapper, never()).insert(any(AiReviewDocument.class));
        verify(documentMapper, never()).updateById(any(AiReviewDocument.class));
    }

    @Test
    void updatesProjectionAndAtomicallyMarksProfileDirtyForChangedSource() {
        ShopReview existing = review(31L, 4L, "Old text", 2, "ACTIVE");
        AiReviewDocument projection = new AiReviewDocument();
        projection.setId(51L);
        when(reviewMapper.selectOne(any())).thenReturn(existing);
        when(reviewMapper.update(any(ShopReview.class), any())).thenReturn(1);
        when(documentMapper.selectOne(any())).thenReturn(projection);
        when(documentMapper.updateById(any(AiReviewDocument.class))).thenReturn(1);

        ShopReview result = service.upsert(request("New text", 4));

        assertEquals(5L, result.getRevision());
        ArgumentCaptor<AiReviewDocument> document = ArgumentCaptor.forClass(AiReviewDocument.class);
        verify(documentMapper).updateById(document.capture());
        assertEquals("New text", document.getValue().getContent());
        assertEquals(5L, document.getValue().getSourceRevision());
        assertEquals(1, document.getValue().getSentiment());
        verify(vectorSyncTaskService).enqueueReviewUpsert(document.getValue());
        verify(profileMapper).markDirty(7L);
    }

    @Test
    void softDeletesReviewRemovesProjectionAndMarksProfileDirty() {
        ShopReview existing = review(31L, 4L, "Old text", 2, "ACTIVE");
        AiReviewDocument projection = new AiReviewDocument();
        projection.setId(51L);
        projection.setShopId(7L);
        when(reviewMapper.selectOne(any())).thenReturn(existing);
        when(reviewMapper.update(any(ShopReview.class), any())).thenReturn(1);
        when(documentMapper.selectOne(any())).thenReturn(projection);
        when(documentMapper.deleteById(51L)).thenReturn(1);

        assertTrue(service.delete("user", "external-7"));
        assertEquals("DELETED", existing.getStatus());
        assertEquals(5L, existing.getRevision());
        verify(vectorSyncTaskService).enqueueReviewDelete(projection, 5L);
        verify(documentMapper).deleteById(51L);
        verify(profileMapper).markDirty(7L);
    }

    @Test
    void deletingAnAlreadyDeletedReviewDoesNotDirtyProfileAgain() {
        when(reviewMapper.selectOne(any())).thenReturn(review(31L, 4L, "Old text", 2, "DELETED"));

        assertFalse(service.delete("user", "external-7"));

        verify(profileMapper, never()).markDirty(eq(7L));
    }

    private ShopReviewUpsertRequest request(String content, int rating) {
        ShopReviewUpsertRequest request = new ShopReviewUpsertRequest();
        request.setShopId(7L);
        request.setSourceType("user");
        request.setSourceKey("external-7");
        request.setUserId(88L);
        request.setRating(rating);
        request.setContent(content);
        return request;
    }

    private ShopReview review(Long id, Long revision, String content, int rating, String status) {
        ShopReview review = new ShopReview();
        review.setId(id);
        review.setShopId(7L);
        review.setSourceType("USER");
        review.setSourceKey("external-7");
        review.setUserId(88L);
        review.setRating(rating);
        review.setContent(content);
        review.setStatus(status);
        review.setRevision(revision);
        return review;
    }
}
