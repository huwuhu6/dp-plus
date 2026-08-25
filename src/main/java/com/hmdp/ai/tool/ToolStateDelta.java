package com.hmdp.ai.tool;

import com.hmdp.ai.dto.DecisionRecommendation;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * A tool may describe a state consequence, but it must never mutate session state itself.
 * The outer reducer owns whether and how this delta is persisted.
 */
@Data
public class ToolStateDelta {
    private Long focusedShopId;
    private String focusedShopName;
    private List<DecisionRecommendation> candidatePoolAppend = new ArrayList<DecisionRecommendation>();
}
