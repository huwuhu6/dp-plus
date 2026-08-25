package com.hmdp.ai.service;

import com.hmdp.ai.dto.ChatMessageResponse;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.DecisionResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatStreamServiceTest {
    @Test
    void recommendationResultProducesDeterministicFollowUpChips() {
        ChatStreamService service = new ChatStreamService();
        DecisionRecommendation recommendation = new DecisionRecommendation();
        recommendation.setShopId(77L);
        recommendation.setShopName("朝天门火锅（上街大学城店）");
        DecisionResponse decision = new DecisionResponse();
        decision.getRecommendations().add(recommendation);
        ChatMessageResponse response = new ChatMessageResponse();
        response.setDecision(decision);

        List<Map<String, String>> chips = service.suggestedChips(response);

        assertEquals(3, chips.size());
        assertEquals("第一家评价如何？", chips.get(0).get("query"));
        assertTrue(chips.get(1).get("query").contains("优惠券"));
    }

    @Test
    void nonRecommendationDoesNotInventChips() {
        ChatStreamService service = new ChatStreamService();
        assertTrue(service.suggestedChips(new ChatMessageResponse()).isEmpty());
    }
}
