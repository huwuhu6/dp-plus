package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class ContextRewriteResult {
    private String originalQuery;
    private String rewrittenQuery;
    private Boolean applied;
    private Boolean usedModel;
    private String reason;
    private RewriteIntentType intentType = RewriteIntentType.GENERAL_CHAT;
    private Integer candidateOrdinal;
    private List<ReferenceIntent> referenceIntents = new ArrayList<ReferenceIntent>();

    public static ContextRewriteResult unchanged(String query, String reason) {
        ContextRewriteResult result = new ContextRewriteResult();
        result.setOriginalQuery(query);
        result.setRewrittenQuery(query);
        result.setApplied(false);
        result.setUsedModel(false);
        result.setReason(reason);
        result.setIntentType(RewriteIntentType.GENERAL_CHAT);
        return result;
    }
}
