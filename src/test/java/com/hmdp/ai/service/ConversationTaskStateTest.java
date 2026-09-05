package com.hmdp.ai.service;

import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.dto.ConstraintSource;
import com.hmdp.ai.dto.CriteriaMergeResult;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionTaskState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationTaskStateTest {
    @Test
    void createsTasksSwitchesToFirstAndKeepsTaskLocalCriteria() {
        ConversationStateService state = new ConversationStateService();
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        DecisionTaskState a = state.createTask(memory, "福州火锅");
        a.getCriteria().setCuisine("火锅"); a.getCriteria().setBudgetPerPerson(100);
        DecisionTaskState b = state.createTask(memory, "杭州日料");
        b.getCriteria().setCuisine("日料"); b.getCriteria().setBudgetPerPerson(300);
        assertTrue(state.activateHistoricalTask(memory, "回到最开始那个"));
        assertEquals(a.getTaskId(), memory.getActiveTaskId());
        assertEquals(100, memory.getActiveCriteria().getBudgetPerPerson());
        memory.getActiveCriteria().setBudgetPerPerson(80);
        assertEquals(300, b.getCriteria().getBudgetPerPerson());
    }

    @Test
    void recordsExplicitAndDerivedConstraintSources() {
        ConversationStateService state = new ConversationStateService();
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        DecisionTaskState task = state.createTask(memory, "测试");
        CriteriaMergeResult explicit = new CriteriaMergeResult(); explicit.setConstraints(new DecisionConstraints());
        explicit.getReplaced().add("radiusKm:-1.0->1.0");
        state.markConstraintSourcesForTest(task, explicit);
        assertEquals(ConstraintSource.USER_EXPLICIT, task.getConstraintSources().get("radiusKm"));
        CriteriaMergeResult derived = new CriteriaMergeResult(); derived.setConstraints(new DecisionConstraints());
        derived.getAppended().add("relativeBudget:anchorPrice=100->budgetPerPerson=85");
        state.markConstraintSourcesForTest(task, derived);
        assertEquals(ConstraintSource.DERIVED, task.getConstraintSources().get("budgetPerPerson"));
    }
}
