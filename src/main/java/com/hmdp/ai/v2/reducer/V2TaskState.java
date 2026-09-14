package com.hmdp.ai.v2.reducer;

import com.hmdp.ai.dto.RecommendationBatch;
import com.hmdp.ai.v2.plan.SearchAnchor;
import com.hmdp.ai.v2.semantic.DiningCriteria;
import com.hmdp.ai.v2.semantic.RequirementChange;
import java.util.List;
import java.util.Set;

/** Immutable canonical task state passed between reducers and the persistence gateway. */
public record V2TaskState(TaskLifecycle lifecycle, DiningCriteria criteria,
                          List<RequirementChange.RelativePreference> relativePreferences,
                          Set<DiningCriteria.PreferenceDimension> relaxable,
                          Set<DiningCriteria.PreferenceDimension> locked, Set<Long> rejectedShopIds,
                          List<V2FeedbackEntry> feedbackLedger, SearchAnchor searchAnchor,
                          Long selectedShopId, List<RecommendationBatch> recommendationBatches) {
    public V2TaskState {
        lifecycle = lifecycle == null ? TaskLifecycle.ACTIVE : lifecycle;
        criteria = criteria == null ? DiningCriteria.empty() : criteria;
        relativePreferences = relativePreferences == null ? List.of() : List.copyOf(relativePreferences);
        relaxable = relaxable == null ? Set.of() : Set.copyOf(relaxable);
        locked = locked == null ? Set.of() : Set.copyOf(locked);
        rejectedShopIds = rejectedShopIds == null ? Set.of() : Set.copyOf(rejectedShopIds);
        feedbackLedger = feedbackLedger == null ? List.of() : List.copyOf(feedbackLedger);
        recommendationBatches = recommendationBatches == null ? List.of() : List.copyOf(recommendationBatches);
    }
    public V2TaskState(DiningCriteria criteria, List<RequirementChange.RelativePreference> relativePreferences,
                       Set<DiningCriteria.PreferenceDimension> relaxable, Set<DiningCriteria.PreferenceDimension> locked) {
        this(TaskLifecycle.ACTIVE, criteria, relativePreferences, relaxable, locked, Set.of(), List.of(), null, null, List.of());
    }

    /** Location resolution changes execution strategy only, never the user's DiningCriteria. */
    public V2TaskState withSearchAnchor(com.hmdp.ai.v2.plan.SearchAnchor anchor) {
        return new V2TaskState(lifecycle, criteria, relativePreferences, relaxable, locked, rejectedShopIds,
                feedbackLedger, anchor, selectedShopId, recommendationBatches);
    }
}
