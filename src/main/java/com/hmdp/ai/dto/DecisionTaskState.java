package com.hmdp.ai.dto;
import lombok.Data;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import com.hmdp.ai.v2.plan.SearchAnchor;
import com.hmdp.ai.v2.semantic.DiningCriteria;
import com.hmdp.ai.v2.semantic.RequirementChange;
import com.hmdp.ai.v2.reducer.TaskLifecycle;
@Data
public class DecisionTaskState {
    private String taskId;
    private String title;
    private DecisionConstraints criteria = new DecisionConstraints();
    private Map<String, ConstraintSource> constraintSources = new LinkedHashMap<>();
    private ConversationLocationSlot searchLocation = new ConversationLocationSlot();
    private List<RecommendationBatch> recommendationBatches = new ArrayList<>();
    /** V2 canonical user requirements. Legacy criteria is an infrastructure projection only. */
    private DiningCriteria v2Criteria = DiningCriteria.empty();
    private List<RequirementChange.RelativePreference> v2RelativePreferences = new ArrayList<>();
    private Set<DiningCriteria.PreferenceDimension> v2Relaxable = new HashSet<>();
    private Set<DiningCriteria.PreferenceDimension> v2Locked = new HashSet<>();
    private Set<Long> v2RejectedShopIds = new HashSet<>();
    private Long v2SelectedShopId;
    private TaskLifecycle v2Lifecycle = TaskLifecycle.ACTIVE;
    private SearchAnchor v2SearchAnchor;
}
