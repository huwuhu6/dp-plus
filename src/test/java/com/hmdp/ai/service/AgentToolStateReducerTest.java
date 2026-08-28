package com.hmdp.ai.service;

import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.tool.AgentToolResult;
import com.hmdp.ai.tool.ToolStateDelta;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentToolStateReducerTest {
    @Test
    void appliesCandidateDeltaWithoutMarkingShownHistory() {
        AgentSessionContext context = new AgentSessionContext();
        AgentToolResult result = new AgentToolResult();
        ToolStateDelta delta = new ToolStateDelta();
        delta.setFocusedShopId(8L); delta.setFocusedShopName("测试店");
        DecisionRecommendation candidate = new DecisionRecommendation();
        candidate.setShopId(9L); candidate.setShopName("备选店");
        delta.getCandidatePoolAppend().add(candidate);
        result.setStateDelta(delta);

        AgentToolStateReducer reducer = new AgentToolStateReducer();
        reducer.apply(context, result);
        reducer.apply(context, result);

        assertEquals(8L, context.getFocusedShopId());
        assertEquals("测试店", context.getFocusedShopName());
        assertEquals(1, context.getCandidatePoolSnapshot().size());
        assertEquals(0, context.getShownShopIdsSnapshot().size());
    }

    @Test
    void marksShownCandidatesOnlyFromUserFacingOutput() {
        AgentSessionContext context = new AgentSessionContext();
        AgentToolResult result = new AgentToolResult().displayText("可继续考虑：备选店");
        ToolStateDelta delta = new ToolStateDelta();
        DecisionRecommendation candidate = new DecisionRecommendation();
        candidate.setShopId(9L); candidate.setShopName("备选店");
        delta.getCandidatePoolAppend().add(candidate);
        result.setStateDelta(delta);

        AgentToolStateReducer reducer = new AgentToolStateReducer();
        reducer.apply(context, result);
        reducer.markShown(context, result);
        reducer.markShown(context, result);

        assertEquals(1, context.getCandidatePoolSnapshot().size());
        assertEquals(java.util.Collections.singletonList(9L), context.getShownShopIdsSnapshot());
    }
}
