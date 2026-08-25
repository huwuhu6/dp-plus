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
            if (candidate == null || candidate.getShopId() == null || context.getShownShopIds().contains(candidate.getShopId())) continue;
            context.getShownShopIds().add(candidate.getShopId());
            context.getShownShops().add(candidate);
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
