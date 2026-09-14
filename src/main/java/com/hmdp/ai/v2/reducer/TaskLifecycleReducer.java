package com.hmdp.ai.v2.reducer;

import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.dto.DecisionTaskState;
import com.hmdp.ai.v2.semantic.TaskDirective;
import java.util.UUID;

/** The only gateway permitted to alter durable task activation/lifecycle. */
public final class TaskLifecycleReducer {
    public DecisionTaskState apply(ConversationWorkingMemory memory, TaskDirective directive, String restoreTaskId) {
        DecisionTaskState active = memory.activeTask();
        return switch (directive) {
            case CONTINUE -> requireActive(active);
            case START_NEW -> startNew(memory, active);
            case RESTORE -> restore(memory, active, restoreTaskId);
            case ABANDON -> abandon(memory, active);
        };
    }
    private DecisionTaskState startNew(ConversationWorkingMemory memory, DecisionTaskState active) {
        if (active != null) active.setV2Lifecycle(TaskLifecycle.SUSPENDED);
        DecisionTaskState created = new DecisionTaskState();
        created.setTaskId(UUID.randomUUID().toString()); created.setTitle("当前推荐");
        created.setV2Lifecycle(TaskLifecycle.ACTIVE); memory.getTasks().add(created); memory.setActiveTaskId(created.getTaskId());
        return created;
    }
    private DecisionTaskState restore(ConversationWorkingMemory memory, DecisionTaskState active, String taskId) {
        if (taskId == null) throw new IllegalArgumentException("RESTORE requires a grounded task");
        DecisionTaskState target = memory.getTasks().stream().filter(t -> taskId.equals(t.getTaskId())).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("RESTORE task does not exist"));
        if (target.getV2Lifecycle() == TaskLifecycle.ABANDONED) throw new IllegalArgumentException("cannot restore an abandoned task");
        if (active != null && active != target) active.setV2Lifecycle(TaskLifecycle.SUSPENDED);
        target.setV2Lifecycle(TaskLifecycle.ACTIVE); memory.setActiveTaskId(target.getTaskId()); return target;
    }
    private DecisionTaskState abandon(ConversationWorkingMemory memory, DecisionTaskState active) {
        DecisionTaskState target = requireActive(active); target.setV2Lifecycle(TaskLifecycle.ABANDONED); memory.setActiveTaskId(null); return target;
    }
    private DecisionTaskState requireActive(DecisionTaskState active) {
        if (active == null || active.getV2Lifecycle() == TaskLifecycle.ABANDONED) throw new IllegalArgumentException("no active task");
        return active;
    }
}
