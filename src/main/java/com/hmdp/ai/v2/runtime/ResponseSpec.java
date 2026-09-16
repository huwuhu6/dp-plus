package com.hmdp.ai.v2.runtime;

import com.hmdp.ai.dto.DecisionRecommendation;
import java.util.List;

/** Closed-world response contract. Rendering never reinterprets the user turn. */
public record ResponseSpec(List<DecisionRecommendation> recommendations, String factAnswer,
                           Long selectedShopId, String selectedShopName, String generalAnswer, String clarification,
                           String partialError, String completionMessage, boolean unsupported, boolean staleSuppressed) {
    public ResponseSpec { recommendations = recommendations == null ? List.of() : List.copyOf(recommendations); }
    public ResponseSpec(List<DecisionRecommendation> recommendations, String factAnswer, Long selectedShopId,
                        String selectedShopName, String generalAnswer, String clarification, String partialError) {
        this(recommendations, factAnswer, selectedShopId, selectedShopName, generalAnswer, clarification, partialError, null, false, false);
    }
    public ResponseSpec(List<DecisionRecommendation> recommendations, String factAnswer, Long selectedShopId,
                        String selectedShopName, String generalAnswer, String clarification, String partialError,
                        boolean unsupported) {
        this(recommendations, factAnswer, selectedShopId, selectedShopName, generalAnswer, clarification,
                partialError, null, unsupported, false);
    }
    public static ResponseSpec completed(String message) {
        return new ResponseSpec(List.of(), null, null, null, null, null, null, message, false, false);
    }
}
