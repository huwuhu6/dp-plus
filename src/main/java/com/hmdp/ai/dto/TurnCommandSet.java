package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Request-scoped semantic interpretation. It deliberately remains ephemeral: durable
 * state is still reduced through CriteriaMergeResult and ConversationStateService.
 */
@Data
public class TurnCommandSet {
    private List<TurnCommand> commands = new ArrayList<>();
    private DecisionContextQuery contextQuery;
    private boolean contextQueryRequested;
    private boolean mutationRequested;
    private boolean referenceOnly;

    public boolean hasCommand(TurnCommand.Type type) {
        if (type == null || commands == null) return false;
        for (TurnCommand command : commands) if (command != null && type == command.getType()) return true;
        return false;
    }
}
