package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ai.entity.AiReviewDocument;
import com.hmdp.ai.entity.AiShopProfile;
import com.hmdp.ai.entity.AiVectorSyncTask;
import com.hmdp.ai.mapper.AiReviewDocumentMapper;
import com.hmdp.ai.mapper.AiShopProfileMapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import java.util.List;

/** Uses stable IDs to replace or delete one vector without changing MySQL facts. */
@Service
public class MilvusSemanticShopIndexWriter implements SemanticShopIndexWriter {
    @Autowired(required = false) private VectorStore vectorStore;
    @Resource private ShopMapper shopMapper;
    @Resource private AiShopProfileMapper profileMapper;
    @Resource private AiReviewDocumentMapper reviewMapper;
    @Resource private SemanticShopDocumentFactory documentFactory;

    @Override
    public void apply(AiVectorSyncTask task) {
        requireVectorStore();
        if (VectorSyncTaskService.DELETE.equals(task.getOperation())) {
            vectorStore.delete(List.of(task.getDocumentId()));
            return;
        }
        Document document = document(task);
        if (document == null) {
            vectorStore.delete(List.of(task.getDocumentId()));
            return;
        }
        // Spring AI 1.0.3 delegates add() to Milvus insert, not a documented upsert.
        // Do not add after a failed delete: retrying is safer than creating duplicate primary-key writes.
        vectorStore.delete(List.of(task.getDocumentId()));
        vectorStore.add(List.of(document));
    }

    private Document document(AiVectorSyncTask task) {
        Shop shop = shopMapper.selectById(task.getShopId());
        if (shop == null) return null;
        if (SemanticShopDocumentFactory.PROFILE.equals(task.getDocumentType())) {
            AiShopProfile profile = profileMapper.selectOne(new QueryWrapper<AiShopProfile>()
                    .eq("shop_id", task.getShopId()).eq("aggregated_revision", task.getTargetRevision()).last("limit 1"));
            return profile == null ? null : documentFactory.profileDocument(shop, profile);
        }
        AiReviewDocument review = reviewMapper.selectById(task.getEntityId());
        if (review == null || value(review.getSourceRevision()) != value(task.getTargetRevision())) return null;
        return documentFactory.reviewDocument(shop, review);
    }

    private void requireVectorStore() {
        if (vectorStore == null) throw new IllegalStateException("Milvus VectorStore is not configured");
    }

    private long value(Long value) { return value == null ? 0L : value; }
}
