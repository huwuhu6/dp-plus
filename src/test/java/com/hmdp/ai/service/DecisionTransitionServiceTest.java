package com.hmdp.ai.service;

import com.hmdp.ai.dto.DecisionOption;
import com.hmdp.ai.entity.AiDecisionSession;
import com.hmdp.ai.runtime.DecisionCommand;
import com.hmdp.ai.runtime.DecisionSideEffect;
import com.hmdp.ai.runtime.DecisionTransition;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DecisionTransitionServiceTest {
    private final DecisionTransitionService transitions = new DecisionTransitionService();

    @Test
    void completesNewDecisionLifecycle() {
        AiDecisionSession session = new AiDecisionSession();

        transitions.transition(session, DecisionCommand.START_DECISION);
        transitions.transition(session, DecisionCommand.EXTRACT_CONSTRAINTS);
        transitions.transition(session, DecisionCommand.COMPLETE);

        assertEquals("COMPLETED", session.getStatus());
        assertTrue(transitions.isTerminal(session.getStatus()));
    }

    @Test
    void pausesForMissingLocationThenResumesWhenLocationProvided() {
        AiDecisionSession session = extractingSession();

        transitions.transition(session, DecisionCommand.REQUIRE_LOCATION);
        transitions.validatePendingState(session.getStatus(), "LOCATION",
                List.of(new DecisionOption("PROVIDE_LOCATION", "提供位置")), "请提供位置");
        transitions.transitionWithLocation(session, DecisionCommand.PROVIDE_LOCATION, 26.1D, 119.3D);
        transitions.transition(session, DecisionCommand.COMPLETE);

        assertEquals("COMPLETED", session.getStatus());
    }

    @Test
    void resumesCitywideWhenLocationIsDeclined() {
        AiDecisionSession session = clarifyingSession();

        transitions.transition(session, DecisionCommand.DECLINE_LOCATION);
        transitions.transition(session, DecisionCommand.COMPLETE);

        assertEquals("COMPLETED", session.getStatus());
    }

    @Test
    void waitsForRelaxationAfterStrictSearchIsEmpty() {
        AiDecisionSession session = extractingSession();

        transitions.transition(session, DecisionCommand.STRICT_SEARCH_EMPTY);
        transitions.validatePendingState(session.getStatus(), "RELAXATION",
                List.of(new DecisionOption("EXPAND_RADIUS", "扩大范围"), new DecisionOption("END_DECISION", "结束")), "没有结果");

        assertEquals("WAITING_RELAXATION", session.getStatus());
    }

    @Test
    void resumesAfterRelaxationAndMayPauseAgain() {
        AiDecisionSession session = extractingSession();
        transitions.transition(session, DecisionCommand.STRICT_SEARCH_EMPTY);

        transitions.transition(session, DecisionCommand.EXPAND_RADIUS);
        assertEquals("RESUMING", session.getStatus());
        transitions.transition(session, DecisionCommand.STRICT_SEARCH_EMPTY);

        assertEquals("WAITING_RELAXATION", session.getStatus());
    }

    @Test
    void cancelsPausedDecision() {
        AiDecisionSession session = extractingSession();
        transitions.transition(session, DecisionCommand.STRICT_SEARCH_EMPTY);

        transitions.transition(session, DecisionCommand.END_DECISION);

        assertEquals("CANCELLED", session.getStatus());
        assertTrue(transitions.isTerminal(session.getStatus()));
    }

    @Test
    void rejectsRelaxationForCompletedDecisionWithoutChangingState() {
        AiDecisionSession session = extractingSession();
        transitions.transition(session, DecisionCommand.COMPLETE);

        assertThrows(IllegalArgumentException.class,
                () -> transitions.transition(session, DecisionCommand.EXPAND_RADIUS));

        assertEquals("COMPLETED", session.getStatus());
    }

    @Test
    void rejectsInvalidOptionStateCombinationWithoutChangingSession() {
        AiDecisionSession session = clarifyingSession();

        assertThrows(IllegalArgumentException.class,
                () -> transitions.transition(session, DecisionCommand.RELAX_CUISINE));

        assertEquals("CLARIFYING", session.getStatus());
    }

    @Test
    void locationCommandsHaveDistinctSemanticsAndRequireCoordinatesToResume() {
        AiDecisionSession session = extractingSession();
        assertEquals("CLARIFYING", transitions.transition(session, DecisionCommand.REQUIRE_LOCATION).getNextState());
        assertThrows(IllegalArgumentException.class,
                () -> transitions.transition(session, DecisionCommand.PROVIDE_LOCATION));
        assertEquals("CLARIFYING", session.getStatus());

        transitions.transitionWithLocation(session, DecisionCommand.PROVIDE_LOCATION, 26.1D, 119.3D);
        assertEquals("RESUMING", session.getStatus());
    }

    @Test
    void switchingCityKeepsNoDataTaskPausedAndRequestsNewSearch() {
        AiDecisionSession session = extractingSession();
        transitions.transition(session, DecisionCommand.NO_DATA_FOUND);

        DecisionTransition transition = transitions.transition(session, DecisionCommand.SWITCH_CITY);

        assertEquals("ZERO_RESULT_NO_DATA", session.getStatus());
        assertEquals("ZERO_RESULT_NO_DATA", transition.getNextState());
        assertTrue(transition.getSideEffects().contains(DecisionSideEffect.REQUEST_NEW_SEARCH));
    }

    @Test
    void rejectsOptionThatWasNotOfferedWithoutChangingState() {
        AiDecisionSession session = extractingSession();
        transitions.transition(session, DecisionCommand.STRICT_SEARCH_EMPTY);

        assertThrows(IllegalArgumentException.class,
                () -> transitions.validateSelectedOption(session.getStatus(), "RELAX_CUISINE",
                        List.of(new DecisionOption("EXPAND_RADIUS", "扩大范围"))));

        assertEquals("WAITING_RELAXATION", session.getStatus());
    }

    @Test
    void enforcesPendingStateInvariants() {
        assertThrows(IllegalStateException.class,
                () -> transitions.validatePendingState("WAITING_RELAXATION", "RELAXATION",
                        List.of(new DecisionOption("END_DECISION", "结束")), "没有结果"));
        assertThrows(IllegalStateException.class,
                () -> transitions.validatePendingState("CLARIFYING", "LOCATION", Collections.emptyList(), ""));
        assertThrows(IllegalStateException.class,
                () -> transitions.validatePendingState("RESUMING", "LOCATION", Collections.emptyList(), null));
    }

    @Test
    void exposesNamedSideEffectsForDecisionCommands() {
        assertTrue(transitions.resolve("WAITING_RELAXATION", DecisionCommand.EXPAND_RADIUS)
                .getSideEffects().contains(DecisionSideEffect.APPLY_RELAXATION));
        assertTrue(transitions.resolve("EXTRACTING", DecisionCommand.REQUIRE_LOCATION)
                .getSideEffects().contains(DecisionSideEffect.REQUIRE_LOCATION));
        assertTrue(transitions.resolve("CLARIFYING", DecisionCommand.PROVIDE_LOCATION)
                .getSideEffects().contains(DecisionSideEffect.APPLY_LOCATION));
        assertFalse(transitions.isTerminal("RESUMING"));
    }

    @Test
    void registersEverySupportedCommandWithOneStableTransitionMeaning() {
        assertEquals("CREATED", transitions.resolve("NEW", DecisionCommand.START_DECISION).getNextState());
        assertEquals("EXTRACTING", transitions.resolve("CREATED", DecisionCommand.EXTRACT_CONSTRAINTS).getNextState());
        assertEquals("RESUMING", transitions.resolve("CREATED", DecisionCommand.EXECUTE).getNextState());
        assertEquals("CLARIFYING", transitions.resolve("EXTRACTING", DecisionCommand.REQUIRE_LOCATION).getNextState());
        assertEquals("RESUMING", transitions.resolve("CLARIFYING", DecisionCommand.PROVIDE_LOCATION).getNextState());
        assertEquals("RESUMING", transitions.resolve("CLARIFYING", DecisionCommand.DECLINE_LOCATION).getNextState());
        assertEquals("EXTRACTING", transitions.resolve("EXTRACTING", DecisionCommand.AUTO_RELAXATION).getNextState());
        assertEquals("WAITING_RELAXATION", transitions.resolve("RESUMING", DecisionCommand.STRICT_SEARCH_EMPTY).getNextState());
        assertEquals("ZERO_RESULT_NO_DATA", transitions.resolve("EXTRACTING", DecisionCommand.NO_DATA_FOUND).getNextState());
        assertEquals("COMPLETED", transitions.resolve("RESUMING", DecisionCommand.COMPLETE).getNextState());
        assertEquals("CANCELLED", transitions.resolve("WAITING_RELAXATION", DecisionCommand.END_DECISION).getNextState());
        assertEquals("FAILED", transitions.resolve("CREATED", DecisionCommand.FAIL).getNextState());
        assertEquals("ZERO_RESULT_NO_DATA", transitions.resolve("ZERO_RESULT_NO_DATA", DecisionCommand.SWITCH_CITY).getNextState());
    }

    @Test
    void mapsAllUserOptionsToDomainCommandsWithoutTreatingThemAsState() {
        for (String option : List.of("PROVIDE_LOCATION", "DECLINE_LOCATION", "END_DECISION",
                "EXPAND_RADIUS", "INCREASE_BUDGET", "RELAX_CUISINE", "RELAX_QUIET",
                "ALLOW_QUEUE", "RELAX_LIGHT_TASTE", "RELAX_HARD_CONSTRAINTS", "SWITCH_CITY")) {
            assertEquals(option, transitions.commandForOption(option).name());
        }
        assertThrows(IllegalArgumentException.class, () -> transitions.commandForOption("REQUIRE_LOCATION"));
    }

    @Test
    void terminalStatesRejectEveryContinuationCommandWithoutMutation() {
        for (String state : List.of("COMPLETED", "CANCELLED", "FAILED")) {
            for (DecisionCommand command : DecisionCommand.values()) {
                assertThrows(IllegalArgumentException.class, () -> transitions.resolve(state, command));
            }
        }
    }

    private AiDecisionSession extractingSession() {
        AiDecisionSession session = new AiDecisionSession();
        transitions.transition(session, DecisionCommand.START_DECISION);
        transitions.transition(session, DecisionCommand.EXTRACT_CONSTRAINTS);
        return session;
    }

    private AiDecisionSession clarifyingSession() {
        AiDecisionSession session = extractingSession();
        transitions.transition(session, DecisionCommand.REQUIRE_LOCATION);
        return session;
    }
}
