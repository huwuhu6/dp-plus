package com.hmdp.ai.service;

import com.hmdp.ai.dto.SemanticRecallResult;
import com.hmdp.entity.Shop;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MilvusSemanticShopRetrieverTest {
    @Test
    void onlyKeepsHighScoreDocumentsForHardFilteredShops() {
        MilvusSemanticShopRetriever retriever = new MilvusSemanticShopRetriever();
        VectorStore vectorStore = mock(VectorStore.class);
        ReflectionTestUtils.setField(retriever, "vectorStore", vectorStore);
        ReflectionTestUtils.setField(retriever, "semanticTopK", 20);
        ReflectionTestUtils.setField(retriever, "semanticMinScore", 0.35D);

        Document accepted = document(1L, 0.81D);
        Document lowScore = document(1L, 0.20D);
        Document outsideHardFilter = document(2L, 0.95D);
        when(vectorStore.similaritySearch(any(SearchRequest.class))).thenReturn(List.of(accepted, lowScore, outsideHardFilter));

        Shop allowed = new Shop();
        allowed.setId(1L);
        SemanticRecallResult result = retriever.recall("安静的日料", List.of(allowed), Map.of(), Map.of());

        assertTrue(result.isAvailable());
        assertEquals(3, result.getMatchedDocumentCount());
        assertEquals(1, result.getAcceptedDocumentCount());
        assertEquals(2, result.getDiscardedDocumentCount());
        assertEquals(0.81D, result.getShopScores().get(1L));
        assertEquals(1, result.getShopScores().size());
    }

    private Document document(Long shopId, Double score) {
        Document document = mock(Document.class);
        when(document.getMetadata()).thenReturn(Map.of("shopId", shopId));
        when(document.getScore()).thenReturn(score);
        return document;
    }
}
