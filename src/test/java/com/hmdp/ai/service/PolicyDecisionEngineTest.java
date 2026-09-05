package com.hmdp.ai.service;

import com.hmdp.ai.dto.ConversationLocationSlot;
import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyDecisionEngineTest {
    private final PolicyDecisionEngine engine = new PolicyDecisionEngine();

    @Test
    void asksForLocationWhenRecommendationHasNoGeographicAnchor() {
        assertEquals(PolicyDecisionEngine.CLARIFY_LOCATION,
                engine.decideRecommendation(new DecisionRequest(), new DecisionConstraints(),
                        new ConversationWorkingMemory()).getAction());
    }

    @Test
    void explicitDestinationWinsOverDeviceLocation() {
        DecisionRequest request = new DecisionRequest();
        request.setLatitude(26.08D); request.setLongitude(119.19D);
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        ConversationLocationSlot device = memory.getLocation();
        device.setStatus("AVAILABLE"); device.setLatitude(26.08D); device.setLongitude(119.19D);

        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setTargetCity("重庆");
        assertEquals(PolicyDecisionEngine.EXECUTE_RECOMMENDATION,
                engine.decideRecommendation(request, constraints, memory).getAction());
    }

    @Test
    void confirmedSearchDestinationAllowsRecommendation() {
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        ConversationLocationSlot target = memory.ensureActiveTask().getSearchLocation();
        target.setStatus("AVAILABLE"); target.setLatitude(29.56D); target.setLongitude(106.55D);

        assertEquals(PolicyDecisionEngine.EXECUTE_RECOMMENDATION,
                engine.decideRecommendation(new DecisionRequest(), new DecisionConstraints(), memory).getAction());
    }

    @Test
    void currentDeviceIntentCanReuseAuthorizedDeviceLocationWithoutNearbyFlag() {
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        ConversationLocationSlot device = memory.getLocation();
        device.setStatus("AVAILABLE"); device.setLatitude(26.08D); device.setLongitude(119.19D);
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setLocationIntent("CURRENT_DEVICE");

        assertEquals(PolicyDecisionEngine.EXECUTE_RECOMMENDATION,
                engine.decideRecommendation(new DecisionRequest(), constraints, memory).getAction());
    }

    @Test
    void classifiesFollowUpActionsWithoutModel() {
        assertEquals(PolicyDecisionEngine.SHOP_VOUCHER, engine.decideFollowUp("这家有优惠券吗").getAction());
        assertEquals(PolicyDecisionEngine.SHOP_EVIDENCE, engine.decideFollowUp("评价怎么样").getAction());
        assertEquals(PolicyDecisionEngine.COMPARE_SHOPS, engine.decideFollowUp("这两家哪个更适合").getAction());
        assertTrue(engine.decideFollowUp("地址在哪").getReason().contains("事实"));
    }
}
