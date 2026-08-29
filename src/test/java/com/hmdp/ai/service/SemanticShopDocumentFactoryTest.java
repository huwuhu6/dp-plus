package com.hmdp.ai.service;

import com.hmdp.ai.entity.AiReviewDocument;
import com.hmdp.ai.entity.AiShopProfile;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticShopDocumentFactoryTest {
    private final SemanticShopDocumentFactory factory = new SemanticShopDocumentFactory();

    @Test
    void keepsStableIdsAndAddsOnlyDiagnosticRevisions() {
        Shop shop = new Shop(); shop.setId(7L); shop.setName("测试店");
        AiShopProfile profile = new AiShopProfile();
        profile.setCuisine("日料"); profile.setSceneTags("约会"); profile.setAmbienceTags("安静");
        profile.setSummary("适合聊天"); profile.setAggregatedRevision(11L);
        AiReviewDocument review = new AiReviewDocument();
        review.setId(31L); review.setContent("环境很好"); review.setTags("安静");
        review.setSourceType("SHOP_REVIEW"); review.setSourceRevision(4L);

        Document profileDocument = factory.profileDocument(shop, profile);
        Document reviewDocument = factory.reviewDocument(shop, review);

        assertEquals("shop-profile-7", profileDocument.getId());
        assertEquals(11L, profileDocument.getMetadata().get("profileRevision"));
        assertTrue(profileDocument.getText().contains("菜系：日料"));
        assertEquals("shop-review-31", reviewDocument.getId());
        assertEquals(4L, reviewDocument.getMetadata().get("sourceRevision"));
        assertEquals("SHOP_REVIEW", reviewDocument.getMetadata().get("sourceType"));
    }
}
