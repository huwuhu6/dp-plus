package com.hmdp.ai.service;

import com.hmdp.ai.entity.AiReviewDocument;
import com.hmdp.ai.entity.AiVectorSyncTask;
import com.hmdp.ai.mapper.AiReviewDocumentMapper;
import com.hmdp.ai.mapper.AiShopProfileMapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MilvusSemanticShopIndexWriterTest {
    private MilvusSemanticShopIndexWriter writer;
    private VectorStore vectorStore;
    private AiReviewDocumentMapper reviewMapper;

    @BeforeEach
    void setUp() {
        writer = new MilvusSemanticShopIndexWriter();
        vectorStore = mock(VectorStore.class);
        reviewMapper = mock(AiReviewDocumentMapper.class);
        ReflectionTestUtils.setField(writer, "vectorStore", vectorStore);
        ReflectionTestUtils.setField(writer, "shopMapper", mock(ShopMapper.class));
        ReflectionTestUtils.setField(writer, "profileMapper", mock(AiShopProfileMapper.class));
        ReflectionTestUtils.setField(writer, "reviewMapper", reviewMapper);
        ReflectionTestUtils.setField(writer, "documentFactory", new SemanticShopDocumentFactory());
    }

    @Test
    void replacesReviewOnlyAfterStableIdDelete() {
        Shop shop = new Shop(); shop.setId(7L); shop.setName("测试店");
        AiReviewDocument review = new AiReviewDocument();
        review.setId(31L); review.setShopId(7L); review.setSourceRevision(4L); review.setContent("新评价"); review.setTags("");
        review.setSourceType("SHOP_REVIEW");
        ShopMapper shopMapper = (ShopMapper) ReflectionTestUtils.getField(writer, "shopMapper");
        when(shopMapper.selectById(7L)).thenReturn(shop);
        when(reviewMapper.selectById(31L)).thenReturn(review);

        writer.apply(task("UPSERT"));

        var order = inOrder(vectorStore);
        order.verify(vectorStore).delete(java.util.List.of("shop-review-31"));
        order.verify(vectorStore).add(any());
    }

    @Test
    void deletionDoesNotLoadProjection() {
        writer.apply(task("DELETE"));
        verify(vectorStore).delete(java.util.List.of("shop-review-31"));
    }

    private AiVectorSyncTask task(String operation) {
        AiVectorSyncTask task = new AiVectorSyncTask();
        task.setDocumentId("shop-review-31"); task.setDocumentType("REVIEW"); task.setEntityId(31L);
        task.setShopId(7L); task.setOperation(operation); task.setTargetRevision(4L);
        return task;
    }
}
