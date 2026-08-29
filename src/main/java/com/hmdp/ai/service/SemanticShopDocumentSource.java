package com.hmdp.ai.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.hmdp.ai.entity.AiReviewDocument;
import com.hmdp.ai.entity.AiShopProfile;
import com.hmdp.ai.mapper.AiReviewDocumentMapper;
import com.hmdp.ai.mapper.AiShopProfileMapper;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import jakarta.annotation.Resource;
import org.springframework.ai.document.Document;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Owns the MySQL selection boundary shared by full rebuild and reconciliation expected state. */
@Component
public class SemanticShopDocumentSource {
    @Resource private ShopMapper shopMapper;
    @Resource private AiShopProfileMapper profileMapper;
    @Resource private AiReviewDocumentMapper reviewMapper;
    @Resource private SemanticShopDocumentFactory documentFactory;

    public Snapshot loadSnapshot() {
        List<Shop> shops = shopMapper.selectList(null);
        Map<Long, Shop> shopsById = new HashMap<>();
        for (Shop shop : shops) shopsById.put(shop.getId(), shop);
        List<ExpectedDocument> documents = new ArrayList<>();
        for (AiShopProfile profile : profileMapper.selectList(null)) {
            Shop shop = shopsById.get(profile.getShopId());
            if (shop != null) documents.add(new ExpectedDocument(documentFactory.profileDocument(shop, profile),
                    SemanticShopDocumentFactory.PROFILE, shop.getId(), shop.getId(), value(profile.getAggregatedRevision())));
        }
        for (AiReviewDocument review : reviewMapper.selectList(new QueryWrapper<AiReviewDocument>())) {
            Shop shop = shopsById.get(review.getShopId());
            if (shop != null) documents.add(new ExpectedDocument(documentFactory.reviewDocument(shop, review),
                    SemanticShopDocumentFactory.REVIEW, review.getId(), shop.getId(), value(review.getSourceRevision())));
        }
        return new Snapshot(shops.size(), List.copyOf(documents));
    }

    private long value(Long value) { return value == null ? 0L : value; }

    public record Snapshot(int shopCount, List<ExpectedDocument> documents) { }
    public record ExpectedDocument(Document document, String documentType, Long entityId, Long shopId, long revision) { }
}
