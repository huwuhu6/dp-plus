package com.hmdp.ai.v2.runtime;

import com.hmdp.ai.dto.DecisionRecommendation;
import java.util.List;

/** Closed-world response contract. Rendering never reinterprets the user turn. */
public record ResponseSpec(List<DecisionRecommendation> recommendations, String factAnswer,
                           Long selectedShopId, String generalAnswer, String clarification,
                           String partialError) {
    public ResponseSpec { recommendations = recommendations == null ? List.of() : List.copyOf(recommendations); }
}
