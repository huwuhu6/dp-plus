package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class ContextRewriteResult {
    private String originalQuery;
    private String rewrittenQuery;
    private Boolean applied;
    private Boolean usedModel;
    private String reason;

    public static ContextRewriteResult unchanged(String query, String reason) {
        ContextRewriteResult result = new ContextRewriteResult();
        result.setOriginalQuery(query);
        result.setRewrittenQuery(query);
        result.setApplied(false);
        result.setUsedModel(false);
        result.setReason(reason);
        return result;
    }
}
