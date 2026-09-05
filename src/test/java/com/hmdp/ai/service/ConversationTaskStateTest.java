package com.hmdp.ai.service;

import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.dto.ConstraintSource;
import com.hmdp.ai.dto.CriteriaMergeResult;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionTaskState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationTaskStateTest {
    private final ConversationStateService state = new ConversationStateService();
    private final ConversationCriteriaMerger merger = new ConversationCriteriaMerger();

    @Test
    void transitionCreatesIndependentTaskThenRestoresItBeforeMergingCurrentDelta() {
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        apply(memory, delta("福州", "", "火锅", 100), "帮我在福州找人均100元以内的火锅");
        String aId = memory.getActiveTaskId();

        assertEquals("CREATE", apply(memory, delta("杭州", "西湖", "日料", 300), "改到杭州西湖找人均300元以内的日料").action());
        String bId = memory.getActiveTaskId();
        assertEquals(2, memory.getTasks().size());
        assertCriteria(task(memory, aId), "福州", "", "火锅", 100);
        assertCriteria(task(memory, bId), "杭州", "西湖", "日料", 300);

        assertEquals("SWITCH", apply(memory, delta("福州", "", "火锅", -1), "还是按最开始福州那套火锅方案").action());
        assertEquals(aId, memory.getActiveTaskId());
        assertCriteria(state.activeTask(memory), "福州", "", "火锅", 100);
        assertCriteria(task(memory, bId), "杭州", "西湖", "日料", 300);
    }

    @Test
    void transitionCreatesThreeTasksAndAppliesDeltaToRestoredTaskOnly() {
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        apply(memory, delta("福州", "", "火锅", 100), "福州附近找火锅，人均100元以内");
        String aId = memory.getActiveTaskId();
        apply(memory, delta("杭州", "西湖", "日料", 300), "改到杭州西湖找日料，人均300元以内");
        String bId = memory.getActiveTaskId();
        assertEquals("CREATE", apply(memory, delta("厦门", "思明", "咖啡", -1), "再改到厦门思明找咖啡馆").action());
        assertEquals(3, memory.getTasks().size());

        assertEquals("SWITCH", apply(memory, delta("福州", "", "火锅", 80), "还是最开始福州火锅，不过预算改成80元以内").action());
        assertEquals(aId, memory.getActiveTaskId());
        assertCriteria(task(memory, aId), "福州", "", "火锅", 80);
        assertCriteria(task(memory, bId), "杭州", "西湖", "日料", 300);
    }

    @Test
    void transitionKeepsOrdinaryRefinementsInOneTask() {
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        apply(memory, delta("福州", "", "火锅", 100), "福州火锅100");
        assertEquals("UPDATE", apply(memory, delta("福州", "", "火锅", 80), "福州火锅80").action());
        assertEquals(1, memory.getTasks().size());
        assertEquals("UPDATE", apply(memory, delta("杭州", "西湖", "火锅", 100), "改到杭州西湖继续找火锅，预算保持100").action());
        assertEquals(1, memory.getTasks().size());
        assertCriteria(state.activeTask(memory), "杭州", "西湖", "火锅", 100);
    }

    private ConversationStateService.TaskTransition apply(ConversationWorkingMemory memory, DecisionConstraints delta, String message) {
        ConversationStateService.TaskTransition transition = state.transitionTask(memory, delta, message);
        DecisionConstraints previous = state.activeCriteria(memory);
        if (previous == null) previous = new DecisionConstraints();
        state.ensureActiveTask(memory).setCriteria(merger.merge(previous, delta, message).getConstraints());
        return transition;
    }

    private DecisionConstraints delta(String city, String area, String cuisine, int budget) {
        DecisionConstraints result = new DecisionConstraints();
        result.setTargetCity(city); result.setTargetArea(area); result.setCuisine(cuisine); result.setBudgetPerPerson(budget);
        result.setLocationIntent("EXPLICIT_TARGET");
        return result;
    }

    private DecisionTaskState task(ConversationWorkingMemory memory, String taskId) {
        return memory.getTasks().stream().filter(task -> taskId.equals(task.getTaskId())).findFirst().orElseThrow();
    }

    private void assertCriteria(DecisionTaskState task, String city, String area, String cuisine, int budget) {
        assertEquals(city, task.getCriteria().getTargetCity()); assertEquals(area, task.getCriteria().getTargetArea());
        assertEquals(cuisine, task.getCriteria().getCuisine()); assertEquals(budget, task.getCriteria().getBudgetPerPerson());
    }

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
        assertEquals(100, state.activeCriteria(memory).getBudgetPerPerson());
        state.activeCriteria(memory).setBudgetPerPerson(80);
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
