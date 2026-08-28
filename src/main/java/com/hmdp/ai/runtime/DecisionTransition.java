package com.hmdp.ai.runtime;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Immutable decision-domain transition result. Status values remain database-compatible strings. */
public final class DecisionTransition {
    private final String currentState;
    private final DecisionCommand command;
    private final String nextState;
    private final Set<DecisionSideEffect> sideEffects;

    public DecisionTransition(String currentState, DecisionCommand command, String nextState,
                              DecisionSideEffect... sideEffects) {
        this.currentState = currentState;
        this.command = command;
        this.nextState = nextState;
        this.sideEffects = sideEffects.length == 0 ? Collections.emptySet()
                : Collections.unmodifiableSet(EnumSet.of(sideEffects[0], sideEffects));
    }

    public String getCurrentState() { return currentState; }
    public DecisionCommand getCommand() { return command; }
    public String getNextState() { return nextState; }
    public Set<DecisionSideEffect> getSideEffects() { return sideEffects; }
}
