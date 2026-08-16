package com.hmdp.ai.dto;

import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class SemanticRecallResult {
    private Map<Long, Double> shopScores = new LinkedHashMap<>();
    private int matchedDocumentCount;
    private int acceptedDocumentCount;
    private int discardedDocumentCount;
    private long durationMs;
    private boolean available;

    public static SemanticRecallResult unavailable() {
        SemanticRecallResult result = new SemanticRecallResult();
        result.setShopScores(Collections.emptyMap());
        return result;
    }
}
