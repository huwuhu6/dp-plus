package com.hmdp.ai.v2.semantic;

import java.math.BigDecimal;
import java.util.List;

/** 仅承载用户明确表达的餐饮条件，搜索策略的推导值不能写入这里。 */
public record DiningCriteria(LocationCriteria location, CuisineCriteria cuisine, BudgetCriteria budget,
                             DistanceCriteria distance, DiningTimeCriteria diningTime,
                             SemanticPreferences preferences, Integer partySize) {
    public DiningCriteria(LocationCriteria location, CuisineCriteria cuisine, BudgetCriteria budget,
                          DistanceCriteria distance, DiningTimeCriteria diningTime, SemanticPreferences preferences) {
        this(location,cuisine,budget,distance,diningTime,preferences,null);
    }
    public DiningCriteria { if (partySize != null && partySize < 1) throw new IllegalArgumentException("partySize must be positive"); }
    public static DiningCriteria empty() { return new DiningCriteria(null, null, null, null, null, SemanticPreferences.empty()); }
    public record LocationCriteria(String city, String district, String poi) { }
    public record CuisineCriteria(String include, List<String> exclude, List<String> anyOf) {
        public CuisineCriteria(String include, List<String> exclude) { this(include,exclude,List.of()); }
        public CuisineCriteria {
            exclude = exclude == null ? List.of() : List.copyOf(exclude);
            anyOf = anyOf == null ? List.of() : anyOf.stream().map(String::trim).distinct().toList();
            if (anyOf.stream().anyMatch(String::isBlank) || include != null && !include.isBlank() && !anyOf.isEmpty())
                throw new IllegalArgumentException("single cuisine and OR cuisines are mutually exclusive");
        }
        /** 单选与 OR 是互斥表示；消费者统一使用这一集合，绝不能把 OR 编译成 AND。 */
        public List<String> acceptedCuisines() { return include == null || include.isBlank() ? anyOf : List.of(include); }
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
