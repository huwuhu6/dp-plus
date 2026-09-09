package com.hmdp.ai.service;

import com.hmdp.ai.dto.CriteriaDeltaOperation;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.SemanticAct;
import com.hmdp.ai.dto.StructuredUnderstandingResult;
import com.hmdp.ai.dto.TurnSemanticIR;
import com.hmdp.ai.service.pipeline.ChatProcessingAction;
import com.hmdp.ai.util.CuisineCanonicalizer;
import com.hmdp.ai.util.PreferenceCanonicalizer;
import org.springframework.stereotype.Component;

/** Thin adapter from raw IR to existing constraint/action contracts. */
@Component
public class StructuredUnderstandingAdapter {
    public boolean canApplySafely(StructuredUnderstandingResult result) {
        if (result == null || !result.isValid() || result.getIr() == null) return false;
        TurnSemanticIR ir = result.getIr();
        // References, location authority and open queries still use their existing
        // resolvers in this experiment; they explicitly fail closed to legacy.
        return (ir.getReferences() == null || ir.getReferences().isEmpty())
                && ir.getLocationExpression() == null
                && ir.getDecisionContextQuery() == null
                && (ir.getShopFactQueries() == null || ir.getShopFactQueries().isEmpty())
                && (ir.getAmbiguities() == null || ir.getAmbiguities().isEmpty())
                && hasApplicableAct(ir)
                && supportsAllDeltas(ir);
    }

    public DecisionConstraints toConstraints(TurnSemanticIR ir) {
        DecisionConstraints constraints = new DecisionConstraints();
        if (ir == null || ir.getCriteriaDelta() == null) return constraints;
        for (CriteriaDeltaOperation operation : ir.getCriteriaDelta()) apply(constraints, operation);
        return constraints;
    }

    public ChatProcessingAction actionFor(TurnSemanticIR ir) {
        if (ir == null || ir.getActs() == null || ir.getActs().isEmpty()) return ChatProcessingAction.GENERAL_CHAT;
        for (SemanticAct act : ir.getActs()) {
            if (act == null || act.getType() == null) continue;
            if (act.getType() == SemanticAct.Type.RESET_INTENT) return ChatProcessingAction.EXIT_DECISION;
            if (act.getType() == SemanticAct.Type.REQUEST_RECOMMENDATION
                    || act.getType() == SemanticAct.Type.MUTATE_CRITERIA
                    || act.getType() == SemanticAct.Type.EXPLORE_ALTERNATIVE) return ChatProcessingAction.START_DECISION;
        }
        return ChatProcessingAction.GENERAL_CHAT;
    }

    private boolean hasApplicableAct(TurnSemanticIR ir) {
        if (ir.getActs() == null || ir.getActs().isEmpty()) return false;
        for (SemanticAct act : ir.getActs()) {
            if (act == null || act.getType() == null) continue;
            if (act.getType() == SemanticAct.Type.REQUEST_RECOMMENDATION
                    || act.getType() == SemanticAct.Type.MUTATE_CRITERIA
                    || act.getType() == SemanticAct.Type.EXPLORE_ALTERNATIVE
                    || act.getType() == SemanticAct.Type.RESET_INTENT) return true;
        }
        return false;
    }

    private boolean supportsAllDeltas(TurnSemanticIR ir) {
        if (ir.getCriteriaDelta() == null) return true;
        for (CriteriaDeltaOperation item : ir.getCriteriaDelta()) {
            if (item == null || item.getField() == null || item.getOperation() == null) return false;
            CriteriaDeltaOperation.Operation operation = item.getOperation();
            switch (item.getField()) {
                case CUISINE, KEYWORD, ARRIVAL_TIME -> {
                    if (operation != CriteriaDeltaOperation.Operation.SET
                            && operation != CriteriaDeltaOperation.Operation.CLEAR) return false;
                }
                case EXCLUDED_CUISINE -> {
                    if (operation != CriteriaDeltaOperation.Operation.ADD
                            && operation != CriteriaDeltaOperation.Operation.SET) return false;
                }
                case PREFERENCE -> {
                    if (operation != CriteriaDeltaOperation.Operation.ADD
                            && operation != CriteriaDeltaOperation.Operation.REMOVE
                            && operation != CriteriaDeltaOperation.Operation.CLEAR) return false;
                }
                case BUDGET_PER_PERSON, RADIUS_KM -> {
                    if (operation != CriteriaDeltaOperation.Operation.SET
                            && operation != CriteriaDeltaOperation.Operation.INCREASE
                            && operation != CriteriaDeltaOperation.Operation.DECREASE) return false;
                }
                case NEARBY -> {
                    if (operation != CriteriaDeltaOperation.Operation.SET
                            && operation != CriteriaDeltaOperation.Operation.CLEAR) return false;
                }
            }
        }
        return true;
    }

    private void apply(DecisionConstraints target, CriteriaDeltaOperation operation) {
        if (operation == null || operation.getField() == null || operation.getOperation() == null) return;
        String value = operation.getRawValue() == null ? "" : operation.getRawValue().trim();
        switch (operation.getField()) {
            case CUISINE -> applyCuisine(target, operation, value);
            case KEYWORD -> applyText(target, operation, value, true);
            case PREFERENCE -> applyPreference(target, operation, value);
            case EXCLUDED_CUISINE -> { if (!value.isEmpty()) target.getExcludedCuisines().add(CuisineCanonicalizer.canonicalize(value)); }
            case BUDGET_PER_PERSON -> applyNumber(target, operation, value, true);
            case RADIUS_KM -> applyNumber(target, operation, value, false);
            case NEARBY -> target.setNearby(!"CLEAR".equals(operation.getOperation()) && !"false".equalsIgnoreCase(value));
            case ARRIVAL_TIME -> applyText(target, operation, value, false);
        }
    }

    private void applyCuisine(DecisionConstraints target, CriteriaDeltaOperation operation, String value) {
        if (operation.getOperation() == CriteriaDeltaOperation.Operation.CLEAR) target.getClearedFields().add("cuisine");
        else if (!value.isEmpty()) target.setCuisine(CuisineCanonicalizer.canonicalize(value));
    }

    private void applyText(DecisionConstraints target, CriteriaDeltaOperation operation, String value, boolean keyword) {
        if (operation.getOperation() == CriteriaDeltaOperation.Operation.CLEAR) target.getClearedFields().add(keyword ? "keyword" : "arrivalTime");
        else if (!value.isEmpty()) { if (keyword) target.setKeyword(value); else target.setArrivalTime(value); }
    }

    private void applyPreference(DecisionConstraints target, CriteriaDeltaOperation operation, String value) {
        String canonical = PreferenceCanonicalizer.canonicalize(value);
        if (operation.getOperation() == CriteriaDeltaOperation.Operation.REMOVE) target.getRemovedPreferences().add(canonical);
        else if (operation.getOperation() != CriteriaDeltaOperation.Operation.CLEAR && !canonical.isEmpty()) target.getPreferences().add(canonical);
        else if (operation.getOperation() == CriteriaDeltaOperation.Operation.CLEAR) target.getClearedFields().add("preferences");
    }

    private void applyNumber(DecisionConstraints target, CriteriaDeltaOperation operation, String value, boolean budget) {
        try {
            double parsed = Double.parseDouble(value.replaceAll("[^0-9.]", ""));
            if (budget) target.setBudgetPerPerson((int) Math.round(parsed)); else target.setRadiusKm(parsed);
        } catch (RuntimeException ignored) {
            if (budget) target.setBudgetDirection(operation.getOperation() == CriteriaDeltaOperation.Operation.DECREASE ? -1 : 1);
            else target.setRadiusDirection(operation.getOperation() == CriteriaDeltaOperation.Operation.DECREASE ? -1 : 1);
        }
    }
}
