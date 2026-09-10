package com.hmdp.ai.service;

import com.hmdp.ai.dto.CriteriaDeltaOperation;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.SemanticAct;
import com.hmdp.ai.dto.StructuredUnderstandingResult;
import com.hmdp.ai.dto.TurnSemanticIR;
import com.hmdp.ai.service.pipeline.ChatProcessingAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredUnderstandingAdapterTest {
    @Test
    void adaptsRawCuisineAndPreferenceOperationsToExistingConstraints() {
        TurnSemanticIR ir = new TurnSemanticIR();
        CriteriaDeltaOperation cuisine = new CriteriaDeltaOperation();
        cuisine.setField(CriteriaDeltaOperation.Field.CUISINE);
        cuisine.setOperation(CriteriaDeltaOperation.Operation.SET);
        cuisine.setRawValue("寿司");
        CriteriaDeltaOperation preference = new CriteriaDeltaOperation();
        preference.setField(CriteriaDeltaOperation.Field.PREFERENCE);
        preference.setOperation(CriteriaDeltaOperation.Operation.ADD);
        preference.setRawValue("安静");
        ir.getCriteriaDelta().add(cuisine);
        ir.getCriteriaDelta().add(preference);

        DecisionConstraints constraints = new StructuredUnderstandingAdapter().toConstraints(ir);

        assertEquals("日料", constraints.getCuisine());
        assertTrue(constraints.getPreferences().contains("安静"));
    }

    @Test
    void onlySafeSimpleCriteriaTurnCanEnterActiveAdapter() {
        TurnSemanticIR ir = new TurnSemanticIR();
        SemanticAct act = new SemanticAct();
        act.setType(SemanticAct.Type.REQUEST_RECOMMENDATION);
        ir.getActs().add(act);
        StructuredUnderstandingResult result = new StructuredUnderstandingResult();
        result.setValid(true);
        result.setIr(ir);

        StructuredUnderstandingAdapter adapter = new StructuredUnderstandingAdapter();
        assertTrue(adapter.canApplySafely(result));
        assertEquals(ChatProcessingAction.START_DECISION, adapter.actionFor(ir));

        ir.setAmbiguities(java.util.List.of("uncertain"));
        assertFalse(adapter.canApplySafely(result));
    }

    @Test
    void numericOperationsRespectOperationAndRepresentability() {
        StructuredUnderstandingAdapter adapter = new StructuredUnderstandingAdapter();

        TurnSemanticIR setBudget = irWith(CriteriaDeltaOperation.Field.BUDGET_PER_PERSON,
                CriteriaDeltaOperation.Operation.SET, "100");
        assertTrue(adapter.canApplySafely(valid(setBudget)));
        assertEquals(100, adapter.toConstraints(setBudget).getBudgetPerPerson());

        TurnSemanticIR unknownAbsolute = irWith(CriteriaDeltaOperation.Field.BUDGET_PER_PERSON,
                CriteriaDeltaOperation.Operation.SET, "一百");
        assertFalse(adapter.canApplySafely(valid(unknownAbsolute)));

        TurnSemanticIR decrease = irWith(CriteriaDeltaOperation.Field.BUDGET_PER_PERSON,
                CriteriaDeltaOperation.Operation.DECREASE, "便宜点");
        assertTrue(adapter.canApplySafely(valid(decrease)));
        assertEquals(-1, adapter.toConstraints(decrease).getBudgetDirection());

        TurnSemanticIR increase = irWith(CriteriaDeltaOperation.Field.BUDGET_PER_PERSON,
                CriteriaDeltaOperation.Operation.INCREASE, "贵一点");
        assertTrue(adapter.canApplySafely(valid(increase)));
        assertEquals(1, adapter.toConstraints(increase).getBudgetDirection());

        TurnSemanticIR relativeAmount = irWith(CriteriaDeltaOperation.Field.BUDGET_PER_PERSON,
                CriteriaDeltaOperation.Operation.DECREASE, "便宜20元");
        assertFalse(adapter.canApplySafely(valid(relativeAmount)));

        TurnSemanticIR radiusSet = irWith(CriteriaDeltaOperation.Field.RADIUS_KM,
                CriteriaDeltaOperation.Operation.SET, "1.5公里");
        assertTrue(adapter.canApplySafely(valid(radiusSet)));
        assertEquals(1.5D, adapter.toConstraints(radiusSet).getRadiusKm());
        TurnSemanticIR radiusAmount = irWith(CriteriaDeltaOperation.Field.RADIUS_KM,
                CriteriaDeltaOperation.Operation.INCREASE, "远2公里");
        assertFalse(adapter.canApplySafely(valid(radiusAmount)));
    }

    @Test
    void nearbyClearAndUnsupportedActsFailClosedOrMapDeterministically() {
        StructuredUnderstandingAdapter adapter = new StructuredUnderstandingAdapter();
        TurnSemanticIR nearbySet = irWith(CriteriaDeltaOperation.Field.NEARBY,
                CriteriaDeltaOperation.Operation.SET, "true");
        assertTrue(adapter.toConstraints(nearbySet).getNearby());
        TurnSemanticIR nearbyClear = irWith(CriteriaDeltaOperation.Field.NEARBY,
                CriteriaDeltaOperation.Operation.CLEAR, "");
        assertFalse(adapter.toConstraints(nearbyClear).getNearby());

        TurnSemanticIR supportedPair = new TurnSemanticIR();
        supportedPair.getActs().add(act(SemanticAct.Type.REQUEST_RECOMMENDATION));
        supportedPair.getActs().add(act(SemanticAct.Type.MUTATE_CRITERIA));
        assertTrue(adapter.canApplySafely(valid(supportedPair)));
        assertEquals(ChatProcessingAction.START_DECISION, adapter.actionFor(supportedPair));

        for (SemanticAct.Type unsupported : java.util.List.of(SemanticAct.Type.ASK_SHOP_FACT,
                SemanticAct.Type.ASK_DECISION_CONTEXT, SemanticAct.Type.EXPLORE_ALTERNATIVE,
                SemanticAct.Type.RESET_INTENT, SemanticAct.Type.CHITCHAT_OR_UNKNOWN)) {
            TurnSemanticIR unsupportedOnly = new TurnSemanticIR();
            unsupportedOnly.getActs().add(act(unsupported));
            assertFalse(adapter.canApplySafely(valid(unsupportedOnly)), unsupported.name());
        }
        TurnSemanticIR mixed = new TurnSemanticIR();
        mixed.getActs().add(act(SemanticAct.Type.REQUEST_RECOMMENDATION));
        mixed.getActs().add(act(SemanticAct.Type.ASK_SHOP_FACT));
        assertFalse(adapter.canApplySafely(valid(mixed)));
    }

    @Test
    void anchoredDeltaIsNeverAppliedDuringFirstActivePhase() {
        StructuredUnderstandingAdapter adapter = new StructuredUnderstandingAdapter();
        TurnSemanticIR ir = irWith(CriteriaDeltaOperation.Field.BUDGET_PER_PERSON,
                CriteriaDeltaOperation.Operation.DECREASE, "便宜点");
        ir.getCriteriaDelta().get(0).setAnchorReferenceId("r1");
        assertFalse(adapter.canApplySafely(valid(ir)));
    }

    private TurnSemanticIR irWith(CriteriaDeltaOperation.Field field,
                                  CriteriaDeltaOperation.Operation operation,
                                  String rawValue) {
        TurnSemanticIR ir = new TurnSemanticIR();
        ir.getActs().add(act(SemanticAct.Type.MUTATE_CRITERIA));
        CriteriaDeltaOperation delta = new CriteriaDeltaOperation();
        delta.setField(field);
        delta.setOperation(operation);
        delta.setRawValue(rawValue);
        ir.getCriteriaDelta().add(delta);
        return ir;
    }

    private SemanticAct act(SemanticAct.Type type) {
        SemanticAct act = new SemanticAct();
        act.setType(type);
        return act;
    }

    private StructuredUnderstandingResult valid(TurnSemanticIR ir) {
        StructuredUnderstandingResult result = new StructuredUnderstandingResult();
        result.setValid(true);
        result.setIr(ir);
        return result;
    }
}
