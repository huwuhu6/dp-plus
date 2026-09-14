package com.hmdp.ai.v2.semantic;

import java.math.BigDecimal;
import java.util.List;

/** 仅承载用户明确表达的餐饮条件，搜索策略的推导值不能写入这里。 */
public record DiningCriteria(LocationCriteria location, CuisineCriteria cuisine, BudgetCriteria budget,
                             DistanceCriteria distance, DiningTimeCriteria diningTime,
                             SemanticPreferences preferences) {
    public static DiningCriteria empty() { return new DiningCriteria(null, null, null, null, null, SemanticPreferences.empty()); }
    public record LocationCriteria(String city, String district, String poi) { }
    public record CuisineCriteria(String include, List<String> exclude) {
        public CuisineCriteria { exclude = exclude == null ? List.of() : List.copyOf(exclude); }
    }
    public record BudgetCriteria(BigDecimal softTarget, BigDecimal hardMax) {
        public BudgetCriteria {
            if (softTarget != null && softTarget.signum() < 0 || hardMax != null && hardMax.signum() < 0)
                throw new IllegalArgumentException("budget cannot be negative");
            if (softTarget != null && hardMax != null && softTarget.compareTo(hardMax) > 0)
                throw new IllegalArgumentException("soft target cannot exceed hard maximum");
        }
    }
    public record DistanceCriteria(BigDecimal hardMaxKm) { }
    public record DiningTimeCriteria(String arrivalTime) { }
    public record SemanticPreferences(List<SemanticPreference> values, List<PreferenceDimension> priorityOrder) {
        public SemanticPreferences { values = values == null ? List.of() : List.copyOf(values); priorityOrder = priorityOrder == null ? List.of() : List.copyOf(priorityOrder); }
        public static SemanticPreferences empty() { return new SemanticPreferences(List.of(), List.of()); }
    }
    public record SemanticPreference(String criterion, Importance importance) { }
    public enum Importance { MUST, PREFER }
    public enum PreferenceDimension { DISTANCE, PRICE, CUISINE, AMBIENCE, QUEUE, EVIDENCE }
}
