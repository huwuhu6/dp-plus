package com.hmdp.ai.dto;
import lombok.Data;
import java.util.LinkedHashMap;
import java.util.Map;
@Data
public class DecisionTaskState {
    private String taskId;
    private String title;
    private DecisionConstraints criteria = new DecisionConstraints();
    private Map<String, ConstraintSource> constraintSources = new LinkedHashMap<>();
    private ConversationLocationSlot searchLocation = new ConversationLocationSlot();
    private int createdTurnNo;
    private int lastActivatedTurnNo;
}
