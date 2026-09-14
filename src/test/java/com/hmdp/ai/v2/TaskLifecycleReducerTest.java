package com.hmdp.ai.v2;

import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.dto.DecisionTaskState;
import com.hmdp.ai.v2.reducer.TaskLifecycle;
import com.hmdp.ai.v2.reducer.TaskLifecycleReducer;
import com.hmdp.ai.v2.semantic.TaskDirective;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TaskLifecycleReducerTest {
    @Test void startNewSuspendsActiveAndMakesNewTaskActive() {
        ConversationWorkingMemory memory = memory("A");
        DecisionTaskState next = new TaskLifecycleReducer().apply(memory, TaskDirective.START_NEW, null);
        assertEquals(TaskLifecycle.SUSPENDED, memory.getTasks().getFirst().getV2Lifecycle());
        assertEquals(TaskLifecycle.ACTIVE, next.getV2Lifecycle()); assertEquals(next.getTaskId(), memory.getActiveTaskId());
    }
    @Test void restoreAndAbandonChangeDurableActiveTask() {
        ConversationWorkingMemory memory = memory("A");
        DecisionTaskState b = new TaskLifecycleReducer().apply(memory, TaskDirective.START_NEW, null);
        DecisionTaskState a = new TaskLifecycleReducer().apply(memory, TaskDirective.RESTORE, "A");
        assertEquals("A", memory.getActiveTaskId()); assertEquals(TaskLifecycle.SUSPENDED, b.getV2Lifecycle());
        new TaskLifecycleReducer().apply(memory, TaskDirective.ABANDON, null);
        assertNull(memory.getActiveTaskId()); assertEquals(TaskLifecycle.ABANDONED, a.getV2Lifecycle());
    }
    private ConversationWorkingMemory memory(String id) {
        ConversationWorkingMemory memory = new ConversationWorkingMemory(); DecisionTaskState task = new DecisionTaskState();
        task.setTaskId(id); task.setV2Lifecycle(TaskLifecycle.ACTIVE); memory.getTasks().add(task); memory.setActiveTaskId(id); return memory;
    }
}
