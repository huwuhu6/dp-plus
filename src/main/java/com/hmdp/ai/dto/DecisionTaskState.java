package com.hmdp.ai.dto;
import lombok.Data;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
@Data
public class DecisionTaskState {
    private String taskId;
    private String title;
    private DecisionConstraints criteria = new DecisionConstraints();
    private Map<String, ConstraintSource> constraintSources = new LinkedHashMap<>();
    private ConversationLocationSlot searchLocation = new ConversationLocationSlot();
    private List<RecommendationBatch> recommendationBatches = new ArrayList<>();
}
