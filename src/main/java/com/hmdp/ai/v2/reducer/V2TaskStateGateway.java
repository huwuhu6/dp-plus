package com.hmdp.ai.v2.reducer;

import com.hmdp.ai.dto.DecisionTaskState;

/** The single adapter between canonical V2 task state and its durable WorkingMemory row. */
public final class V2TaskStateGateway {
    public V2TaskState read(DecisionTaskState task) {
        return new V2TaskState(task.getV2Lifecycle(), task.getV2Criteria(), task.getV2RelativePreferences(),
                task.getV2Relaxable(), task.getV2Locked(), task.getV2RejectedShopIds(), task.getV2FeedbackLedger(),
                task.getV2SearchAnchor(), task.getV2SelectedShopId(), task.getRecommendationBatches());
    }
    public void write(DecisionTaskState task, V2TaskState state) {
        task.setV2Lifecycle(state.lifecycle()); task.setV2Criteria(state.criteria());
        task.setV2RelativePreferences(state.relativePreferences()); task.setV2Relaxable(state.relaxable());
        task.setV2Locked(state.locked()); task.setV2RejectedShopIds(state.rejectedShopIds());
        task.setV2FeedbackLedger(state.feedbackLedger()); task.setV2SearchAnchor(state.searchAnchor());
        task.setV2SelectedShopId(state.selectedShopId()); task.setRecommendationBatches(state.recommendationBatches());
    }
}
