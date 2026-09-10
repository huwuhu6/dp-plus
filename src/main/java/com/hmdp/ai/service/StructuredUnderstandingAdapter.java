package com.hmdp.ai.service;

import com.hmdp.ai.dto.CriteriaDeltaOperation;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.SemanticAct;
import com.hmdp.ai.dto.StructuredUnderstandingResult;
import com.hmdp.ai.dto.TurnSemanticIR;
import com.hmdp.ai.dto.RoutingCriteriaDeltaV2;
import com.hmdp.ai.dto.RoutingFusionV2Result;
import com.hmdp.ai.dto.RoutingSemanticActV2;
import com.hmdp.ai.dto.RoutingSemanticIRV2;
import com.hmdp.ai.service.pipeline.ChatProcessingAction;
import com.hmdp.ai.util.CuisineCanonicalizer;
import com.hmdp.ai.util.PreferenceCanonicalizer;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/** Thin adapter from raw IR to existing constraint/action contracts. */
@Component
public class StructuredUnderstandingAdapter {
    private static final Set<SemanticAct.Type> ACTIVE_ACTS = EnumSet.of(
            SemanticAct.Type.REQUEST_RECOMMENDATION, SemanticAct.Type.MUTATE_CRITERIA);

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
                && supportsAllActs(ir)
                && hasRequiredActiveMutationPayload(ir)
                && supportsAllDeltas(ir);
    }

    public DecisionConstraints toConstraints(TurnSemanticIR ir) {
        DecisionConstraints constraints = new DecisionConstraints();
        if (ir == null || ir.getCriteriaDelta() == null) return constraints;
        for (CriteriaDeltaOperation operation : ir.getCriteriaDelta()) apply(constraints, operation);
        return constraints;
    }

    public ChatProcessingAction actionFor(TurnSemanticIR ir) {
        return supportsAllActs(ir) ? ChatProcessingAction.START_DECISION : ChatProcessingAction.GENERAL_CHAT;
    }

    /** V2 authority gate: only compact routing fusion without unresolved location authority. */
    public boolean canApplyRoutingFusionSafely(RoutingFusionV2Result result) {
        if (result == null || !result.isValid() || !result.isCriteriaReusable() || result.getIr() == null) return false;
        RoutingSemanticIRV2 ir = result.getIr();
        return ir.getLocationExpression() == null
                && (ir.getAmbiguities() == null || ir.getAmbiguities().isEmpty())
                && supportsAllRoutingActs(ir)
                && supportsAllRoutingDeltas(ir);
    }

    public ChatProcessingAction actionFor(RoutingSemanticIRV2 ir) {
        if (ir == null || ir.getActs() == null || ir.getActs().isEmpty()) return ChatProcessingAction.GENERAL_CHAT;
        boolean recommendation = false;
        for (RoutingSemanticActV2 act : ir.getActs()) {
            if (act == null || act.getType() == null) return ChatProcessingAction.GENERAL_CHAT;
            if (act.getType() == RoutingSemanticActV2.Type.REQUEST_RECOMMENDATION
                    || act.getType() == RoutingSemanticActV2.Type.MUTATE_CRITERIA
                    || act.getType() == RoutingSemanticActV2.Type.EXPLORE_ALTERNATIVE) recommendation = true;
        }
        return recommendation ? ChatProcessingAction.START_DECISION : ChatProcessingAction.GENERAL_CHAT;
    }

    public DecisionConstraints toConstraints(RoutingSemanticIRV2 ir) {
        DecisionConstraints constraints = new DecisionConstraints();
        if (ir == null || ir.getCriteriaDelta() == null) return constraints;
        for (RoutingCriteriaDeltaV2 item : ir.getCriteriaDelta()) {
            if (item == null) continue;
            CriteriaDeltaOperation operation = new CriteriaDeltaOperation();
            operation.setField(item.getField());
            operation.setOperation(item.getOperation());
            operation.setRawValue(item.getRawValue());
            apply(constraints, operation);
        }
        return constraints;
    }

    private boolean supportsAllRoutingActs(RoutingSemanticIRV2 ir) {
        if (ir == null || ir.getActs() == null || ir.getActs().isEmpty()) return false;
        for (RoutingSemanticActV2 act : ir.getActs()) {
            if (act == null || act.getType() == null || act.getType() == RoutingSemanticActV2.Type.CHITCHAT_OR_UNKNOWN) return false;
        }
        return true;
    }

    private boolean supportsAllRoutingDeltas(RoutingSemanticIRV2 ir) {
        if (ir == null || ir.getCriteriaDelta() == null) return false;
        if (ir.getCriteriaDelta().isEmpty()) {
            return ir.getActs() != null && ir.getActs().stream()
                    .noneMatch(act -> act != null && act.getType() == RoutingSemanticActV2.Type.MUTATE_CRITERIA);
        }
        for (RoutingCriteriaDeltaV2 item : ir.getCriteriaDelta()) {
            if (item == null || item.getField() == null || item.getOperation() == null) return false;
            CriteriaDeltaOperation operation = new CriteriaDeltaOperation();
            operation.setField(item.getField());
            operation.setOperation(item.getOperation());
            operation.setRawValue(item.getRawValue());
            TurnSemanticIR probe = new TurnSemanticIR();
            probe.getCriteriaDelta().add(operation);
            if (!supportsAllDeltas(probe)) return false;
        }
        return true;
    }

    private boolean supportsAllActs(TurnSemanticIR ir) {
        if (ir == null) return false;
        if (ir.getActs() == null || ir.getActs().isEmpty()) return false;
        for (SemanticAct act : ir.getActs()) {
            if (act == null || act.getType() == null || !ACTIVE_ACTS.contains(act.getType())) return false;
        }
        return true;
    }

    private boolean supportsAllDeltas(TurnSemanticIR ir) {
        if (ir.getCriteriaDelta() == null) return true;
        for (CriteriaDeltaOperation item : ir.getCriteriaDelta()) {
            if (item == null || item.getField() == null || item.getOperation() == null) return false;
            if (hasText(item.getAnchorReferenceId())) return false;
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
                    if (operation == CriteriaDeltaOperation.Operation.SET && parseAbsoluteNumber(item.getRawValue()) == null) return false;
                    if ((operation == CriteriaDeltaOperation.Operation.INCREASE
                            || operation == CriteriaDeltaOperation.Operation.DECREASE)
                            && hasNumericMagnitude(item.getRawValue())) return false;
                }
                case NEARBY -> {
                    if (operation != CriteriaDeltaOperation.Operation.SET
                            && operation != CriteriaDeltaOperation.Operation.CLEAR) return false;
                }
            }
        }
        return true;
    }

    /** Active currently consumes mutations only through criteriaDelta. */
    private boolean hasRequiredActiveMutationPayload(TurnSemanticIR ir) {
        boolean mutatesCriteria = ir.getActs().stream()
                .anyMatch(act -> act != null && act.getType() == SemanticAct.Type.MUTATE_CRITERIA);
        return !mutatesCriteria || (ir.getCriteriaDelta() != null && !ir.getCriteriaDelta().isEmpty());
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
            case NEARBY -> target.setNearby(operation.getOperation() != CriteriaDeltaOperation.Operation.CLEAR
                    && !"false".equalsIgnoreCase(value));
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
        if (operation.getOperation() == CriteriaDeltaOperation.Operation.SET) {
            Double parsed = parseAbsoluteNumber(value);
            if (parsed == null) return;
            if (budget) target.setBudgetPerPerson((int) Math.round(parsed)); else target.setRadiusKm(parsed);
            return;
        }
        if ((operation.getOperation() != CriteriaDeltaOperation.Operation.INCREASE
                && operation.getOperation() != CriteriaDeltaOperation.Operation.DECREASE)
                || hasNumericMagnitude(value)) return;
        int direction = operation.getOperation() == CriteriaDeltaOperation.Operation.DECREASE ? -1 : 1;
        if (budget) target.setBudgetDirection(direction); else target.setRadiusDirection(direction);
    }

    private Double parseAbsoluteNumber(String rawValue) {
        String normalized = rawValue == null ? "" : rawValue.replaceAll("[^0-9.]", "");
        if (normalized.isBlank() || normalized.chars().filter(ch -> ch == '.').count() > 1) return null;
        try {
            double value = Double.parseDouble(normalized);
            return Double.isFinite(value) && value >= 0D ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean hasNumericMagnitude(String rawValue) {
        return rawValue != null && rawValue.matches(".*(?:[0-9零二三四五六七八九十百千万两半]|一(?!点)).*");
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
