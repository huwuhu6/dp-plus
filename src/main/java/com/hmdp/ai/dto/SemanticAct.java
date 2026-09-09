package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** Linguistic act only; it is deliberately not a ChatProcessingAction. */
@Data
public class SemanticAct {
    public enum Type {
        REQUEST_RECOMMENDATION,
        MUTATE_CRITERIA,
        ASK_SHOP_FACT,
        ASK_DECISION_CONTEXT,
        EXPLORE_ALTERNATIVE,
        RESET_INTENT,
        CHITCHAT_OR_UNKNOWN
    }

    private Type type;
    private SemanticEvidence evidence;
    private List<String> details = new ArrayList<>();
}
