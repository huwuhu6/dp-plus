package com.hmdp.ai.service;

import com.hmdp.ai.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** V2 model tests construct Task/RecommendationBatch, never flat entity context. */
class ConversationStateServiceTest {
    @Test void taskSwitchKeepsCanonicalCriteria() {
        ConversationStateService service = new ConversationStateService(); ConversationWorkingMemory memory = new ConversationWorkingMemory();
        DecisionTaskState a = service.createTask(memory, "福州火锅"); a.getCriteria().setBudgetPerPerson(100);
        DecisionTaskState b = service.createTask(memory, "杭州日料"); b.getCriteria().setBudgetPerPerson(300);
        assertTrue(service.activateHistoricalTask(memory, "回到最开始那个")); service.activeCriteria(memory).setBudgetPerPerson(80);
        assertEquals(80, a.getCriteria().getBudgetPerPerson()); assertEquals(300, b.getCriteria().getBudgetPerPerson());
    }
    @Test void batchProjectionRetainsHistory() {
        ConversationStateService service = new ConversationStateService(); ConversationWorkingMemory memory = new ConversationWorkingMemory();
        TestTaskFixture.append(memory, 10L, Arrays.asList(shop(1), shop(2), shop(3))); TestTaskFixture.append(memory, 11L, Arrays.asList(shop(4), shop(5), shop(6)));
        assertEquals(Arrays.asList(4L,5L,6L), ids(service.latestCandidatePool(memory)));
        assertEquals(Arrays.asList(1L,2L,3L,4L,5L,6L), service.shownShopIds(memory)); assertEquals(2, service.activeTask(memory).getRecommendationBatches().size()); assertEquals(11L, service.latestSourceDecisionSessionId(memory));
    }
    private DecisionRecommendation shop(long id) { DecisionRecommendation value = new DecisionRecommendation(); value.setShopId(id); value.setShopName("shop-" + id); return value; }
    private List<Long> ids(List<DecisionRecommendation> values) { List<Long> result = new ArrayList<>(); for (DecisionRecommendation v : values) result.add(v.getShopId()); return result; }
}
