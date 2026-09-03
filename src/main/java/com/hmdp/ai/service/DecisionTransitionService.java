package com.hmdp.ai.service;

import com.hmdp.ai.entity.AiDecisionSession;
import com.hmdp.ai.runtime.DecisionCommand;
import com.hmdp.ai.runtime.DecisionSideEffect;
import com.hmdp.ai.runtime.DecisionTransition;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns only decision task lifecycle legality. It deliberately does not route chat
 * messages, mutate Working Memory, or execute retrieval and tools.
 */
@Service
public class DecisionTransitionService {
    public static final String NEW = "NEW";
    public static final String CREATED = "CREATED";
    public static final String EXTRACTING = "EXTRACTING";
    public static final String CLARIFYING = "CLARIFYING";
    public static final String RESUMING = "RESUMING";
    public static final String WAITING_RELAXATION = "WAITING_RELAXATION";
    public static final String ZERO_RESULT_NO_DATA = "ZERO_RESULT_NO_DATA";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String CANCELLED = "CANCELLED";

    private final Map<String, Map<DecisionCommand, DecisionTransition>> transitions = new HashMap<>();

    public DecisionTransitionService() {
        register(NEW, DecisionCommand.START_DECISION, CREATED, DecisionSideEffect.PERSIST_REQUEST);
        register(CREATED, DecisionCommand.EXTRACT_CONSTRAINTS, EXTRACTING, DecisionSideEffect.EXTRACT_CONSTRAINTS);
        // Preserve the existing direct execution path for callers that already
        // provide extracted constraints while the task is still newly created.
        register(CREATED, DecisionCommand.EXECUTE, RESUMING, DecisionSideEffect.RETRY_SEARCH);
        register(RESUMING, DecisionCommand.EXECUTE, RESUMING, DecisionSideEffect.RETRY_SEARCH);
        register(EXTRACTING, DecisionCommand.REQUIRE_LOCATION, CLARIFYING,
                DecisionSideEffect.REQUIRE_LOCATION, DecisionSideEffect.PERSIST_PENDING_OPTIONS);
        register(RESUMING, DecisionCommand.REQUIRE_LOCATION, CLARIFYING,
                DecisionSideEffect.REQUIRE_LOCATION, DecisionSideEffect.PERSIST_PENDING_OPTIONS);
        register(CLARIFYING, DecisionCommand.PROVIDE_LOCATION, RESUMING,
                DecisionSideEffect.APPLY_LOCATION, DecisionSideEffect.CLEAR_PENDING_OPTIONS, DecisionSideEffect.RETRY_SEARCH);
        register(CLARIFYING, DecisionCommand.DECLINE_LOCATION, RESUMING,
                DecisionSideEffect.APPLY_LOCATION, DecisionSideEffect.CLEAR_PENDING_OPTIONS, DecisionSideEffect.RETRY_SEARCH);
        register(EXTRACTING, DecisionCommand.AUTO_RELAXATION, EXTRACTING,
                DecisionSideEffect.APPLY_RELAXATION, DecisionSideEffect.RETRY_SEARCH);
        register(RESUMING, DecisionCommand.AUTO_RELAXATION, RESUMING,
                DecisionSideEffect.APPLY_RELAXATION, DecisionSideEffect.RETRY_SEARCH);
        register(EXTRACTING, DecisionCommand.STRICT_SEARCH_EMPTY, WAITING_RELAXATION,
                DecisionSideEffect.PERSIST_PENDING_OPTIONS);
        register(RESUMING, DecisionCommand.STRICT_SEARCH_EMPTY, WAITING_RELAXATION,
                DecisionSideEffect.PERSIST_PENDING_OPTIONS);
        register(EXTRACTING, DecisionCommand.NO_DATA_FOUND, ZERO_RESULT_NO_DATA,
                DecisionSideEffect.PERSIST_PENDING_OPTIONS);
        register(RESUMING, DecisionCommand.NO_DATA_FOUND, ZERO_RESULT_NO_DATA,
                DecisionSideEffect.PERSIST_PENDING_OPTIONS);
        register(EXTRACTING, DecisionCommand.COMPLETE, COMPLETED,
                DecisionSideEffect.CLEAR_PENDING_OPTIONS, DecisionSideEffect.PERSIST_RESULT);
        register(RESUMING, DecisionCommand.COMPLETE, COMPLETED,
                DecisionSideEffect.CLEAR_PENDING_OPTIONS, DecisionSideEffect.PERSIST_RESULT);
        // B 修复 #case30：WAITING_RELAXATION 态下用户补充位置 → 恢复（保留约束换位置重搜），
        // 与 CLARIFYING 的 PROVIDE_LOCATION 同语义。TODO(C重构): 与路由层转移表统一。
        register(WAITING_RELAXATION, DecisionCommand.PROVIDE_LOCATION, RESUMING,
                DecisionSideEffect.APPLY_LOCATION, DecisionSideEffect.CLEAR_PENDING_OPTIONS, DecisionSideEffect.RETRY_SEARCH);
        registerRelaxation(DecisionCommand.EXPAND_RADIUS);
        registerRelaxation(DecisionCommand.INCREASE_BUDGET);
        registerRelaxation(DecisionCommand.RELAX_CUISINE);
        registerRelaxation(DecisionCommand.RELAX_QUIET);
        registerRelaxation(DecisionCommand.ALLOW_QUEUE);
        registerRelaxation(DecisionCommand.RELAX_LIGHT_TASTE);
        registerRelaxation(DecisionCommand.RELAX_HARD_CONSTRAINTS);
        register(ZERO_RESULT_NO_DATA, DecisionCommand.SWITCH_CITY, ZERO_RESULT_NO_DATA, DecisionSideEffect.REQUEST_NEW_SEARCH);
        register(CLARIFYING, DecisionCommand.END_DECISION, CANCELLED,
                DecisionSideEffect.CLEAR_PENDING_OPTIONS, DecisionSideEffect.CANCEL_TASK, DecisionSideEffect.PERSIST_RESULT);
        register(WAITING_RELAXATION, DecisionCommand.END_DECISION, CANCELLED,
                DecisionSideEffect.CLEAR_PENDING_OPTIONS, DecisionSideEffect.CANCEL_TASK, DecisionSideEffect.PERSIST_RESULT);
        register(ZERO_RESULT_NO_DATA, DecisionCommand.END_DECISION, CANCELLED,
                DecisionSideEffect.CLEAR_PENDING_OPTIONS, DecisionSideEffect.CANCEL_TASK, DecisionSideEffect.PERSIST_RESULT);
        registerFailure(CREATED); registerFailure(EXTRACTING); registerFailure(CLARIFYING);
        registerFailure(RESUMING); registerFailure(WAITING_RELAXATION); registerFailure(ZERO_RESULT_NO_DATA);
    }

    public DecisionCommand commandForOption(String optionId) {
        if (optionId == null || optionId.trim().isEmpty()) throw new IllegalArgumentException("selectedOptionId 不能为空");
        if (!isUserOption(optionId)) throw new IllegalArgumentException("selectedOptionId 不是用户可选命令: " + optionId);
        try {
            return DecisionCommand.valueOf(optionId);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("selectedOptionId 无效: " + optionId);
        }
    }

    public DecisionTransition resolve(String currentState, DecisionCommand command) {
        String state = currentState == null || currentState.trim().isEmpty() ? NEW : currentState;
        Map<DecisionCommand, DecisionTransition> commands = transitions.get(state);
        DecisionTransition transition = commands == null ? null : commands.get(command);
        if (transition == null) {
            throw new IllegalArgumentException("决策状态 " + state + " 不允许命令 " + command);
        }
        return transition;
    }

    public DecisionTransition transition(AiDecisionSession session, DecisionCommand command) {
        if (command == DecisionCommand.PROVIDE_LOCATION && CLARIFYING.equals(session == null ? null : session.getStatus())) {
            throw new IllegalArgumentException("PROVIDE_LOCATION 必须携带用户提交的坐标");
        }
        return transitionInternal(session, command);
    }

    public DecisionTransition transitionWithLocation(AiDecisionSession session, DecisionCommand command,
                                                      Double latitude, Double longitude) {
        if (command != DecisionCommand.PROVIDE_LOCATION || latitude == null || longitude == null) {
            throw new IllegalArgumentException("PROVIDE_LOCATION 必须携带用户提交的坐标");
        }
        return transitionInternal(session, command);
    }

    private DecisionTransition transitionInternal(AiDecisionSession session, DecisionCommand command) {
        if (session == null) throw new IllegalArgumentException("决策会话不能为空");
        DecisionTransition transition = resolve(session.getStatus(), command);
        session.setStatus(transition.getNextState());
        return transition;
    }

    public DecisionCommand validateSelectedOption(String currentState, String optionId, List<?> pendingOptions) {
        DecisionCommand command = commandForOption(optionId);
        resolve(currentState, command);
        if (!containsOption(pendingOptions, optionId)) {
            throw new IllegalArgumentException("selectedOptionId 不属于当前决策的待选项: " + optionId);
        }
        return command;
    }

    public void validatePendingState(String nextState, String pendingType, List<?> options, String question) {
        if (CLARIFYING.equals(nextState)) {
            if (!"LOCATION".equals(pendingType) || isBlank(question) || !containsOption(options, "PROVIDE_LOCATION")) {
                throw new IllegalStateException("CLARIFYING 必须保留待补充位置与 PROVIDE_LOCATION 选项");
            }
        }
        if (WAITING_RELAXATION.equals(nextState)) {
            if (!"RELAXATION".equals(pendingType) || !hasRelaxationOption(options)) {
                throw new IllegalStateException("WAITING_RELAXATION 必须保留合法 relaxation options");
            }
        }
        if (RESUMING.equals(nextState) && pendingType != null) {
            throw new IllegalStateException("RESUMING 不能保留待处理选项");
        }
    }

    public boolean isTerminal(String state) {
        return COMPLETED.equals(state) || CANCELLED.equals(state) || FAILED.equals(state);
    }

    private void registerRelaxation(DecisionCommand command) {
        register(WAITING_RELAXATION, command, RESUMING,
                DecisionSideEffect.APPLY_RELAXATION, DecisionSideEffect.CLEAR_PENDING_OPTIONS, DecisionSideEffect.RETRY_SEARCH);
    }

    private void registerFailure(String state) {
        register(state, DecisionCommand.FAIL, FAILED, DecisionSideEffect.RECORD_FAILURE);
    }

    private void register(String currentState, DecisionCommand command, String nextState, DecisionSideEffect... sideEffects) {
        transitions.computeIfAbsent(currentState, ignored -> new EnumMap<>(DecisionCommand.class))
                .put(command, new DecisionTransition(currentState, command, nextState, sideEffects));
    }

    private boolean hasRelaxationOption(List<?> options) {
        if (options == null) return false;
        for (Object option : options) {
            String id = optionId(option);
            if (id != null && !"END_DECISION".equals(id)) {
                try {
                    DecisionCommand command = commandForOption(id);
                    if (resolve(WAITING_RELAXATION, command) != null) return true;
                } catch (IllegalArgumentException ignored) {
                    // An option not mapped to a waiting-state command is not a relaxation option.
                }
            }
        }
        return false;
    }

    private boolean containsOption(List<?> options, String expected) {
        if (options == null) return false;
        for (Object option : options) if (expected.equals(optionId(option))) return true;
        return false;
    }

    private String optionId(Object option) {
        if (option instanceof String) return (String) option;
        if (option instanceof com.hmdp.ai.dto.DecisionOption) {
            return ((com.hmdp.ai.dto.DecisionOption) option).getId();
        }
        return null;
    }

    private boolean isUserOption(String optionId) {
        switch (optionId) {
            case "PROVIDE_LOCATION": case "DECLINE_LOCATION": case "END_DECISION":
            case "EXPAND_RADIUS": case "INCREASE_BUDGET": case "RELAX_CUISINE":
            case "RELAX_QUIET": case "ALLOW_QUEUE": case "RELAX_LIGHT_TASTE":
            case "RELAX_HARD_CONSTRAINTS": case "SWITCH_CITY":
                return true;
            default:
                return false;
        }
    }

    private boolean isBlank(String value) { return value == null || value.trim().isEmpty(); }
}
