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
}
