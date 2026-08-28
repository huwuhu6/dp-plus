package com.hmdp.ai.service;

import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.tool.AgentToolResult;
import com.hmdp.ai.tool.ToolStateDelta;
import org.springframework.stereotype.Service;

/** Applies tool state deltas deterministically after all tool calls complete. */
@Service
public class AgentToolStateReducer {
    public void apply(AgentSessionContext context, AgentToolResult result) {
        if (context == null || result == null) return;
        ToolStateDelta delta = result.getStateDelta();
        if (delta == null) return;
        if (delta.getFocusedShopId() != null) context.setFocusedShopId(delta.getFocusedShopId());
        if (hasText(delta.getFocusedShopName())) context.setFocusedShopName(delta.getFocusedShopName());
        if (delta.getCandidatePoolAppend() == null) return;
        for (DecisionRecommendation candidate : delta.getCandidatePoolAppend()) {
            if (candidate == null || candidate.getShopId() == null || containsCandidate(context, candidate.getShopId())) continue;
            context.getCandidatePoolSnapshot().add(candidate);
        }
    }

    /** Marks only candidates represented by the final user-facing tool output as shown. */
    public void markShown(AgentSessionContext context, AgentToolResult result) {
        if (context == null || result == null || !hasText(result.getDisplayText()) || result.getStateDelta() == null
                || result.getStateDelta().getCandidatePoolAppend() == null) return;
        for (DecisionRecommendation candidate : result.getStateDelta().getCandidatePoolAppend()) {
            if (candidate != null && candidate.getShopId() != null && !context.getShownShopIdsSnapshot().contains(candidate.getShopId())) {
                context.getShownShopIdsSnapshot().add(candidate.getShopId());
            }
        }
    }

    private boolean containsCandidate(AgentSessionContext context, Long shopId) {
        for (DecisionRecommendation candidate : context.getCandidatePoolSnapshot()) {
            if (candidate != null && shopId.equals(candidate.getShopId())) return true;
        }
        return false;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
