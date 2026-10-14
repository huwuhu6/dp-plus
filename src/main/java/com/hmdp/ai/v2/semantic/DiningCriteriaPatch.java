package com.hmdp.ai.v2.semantic;

/** Partial user delta. Unlike canonical DiningCriteria, null here has the single meaning “untouched this turn”. */
public record DiningCriteriaPatch(DiningCriteria.LocationCriteria location, DiningCriteria.CuisineCriteria cuisine,
                                  DiningCriteria.BudgetCriteria budget, DiningCriteria.DistanceCriteria distance,
                                  DiningCriteria.DiningTimeCriteria diningTime,
                                  DiningCriteria.SemanticPreferences semanticPreferences, Integer partySize,
                                  @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL) DiningCriteria.DietaryRestrictions dietaryRestrictions) {
    public DiningCriteriaPatch(DiningCriteria.LocationCriteria location,DiningCriteria.CuisineCriteria cuisine,
                              DiningCriteria.BudgetCriteria budget,DiningCriteria.DistanceCriteria distance,
                              DiningCriteria.DiningTimeCriteria time,DiningCriteria.SemanticPreferences preferences,Integer partySize) {
        this(location,cuisine,budget,distance,time,preferences,partySize,null);
    }
    public DiningCriteriaPatch(DiningCriteria.LocationCriteria location, DiningCriteria.CuisineCriteria cuisine,
                               DiningCriteria.BudgetCriteria budget, DiningCriteria.DistanceCriteria distance,
                               DiningCriteria.DiningTimeCriteria diningTime, DiningCriteria.SemanticPreferences semanticPreferences) {
        this(location,cuisine,budget,distance,diningTime,semanticPreferences,null);
    }
    public DiningCriteriaPatch { if (partySize != null && partySize < 1) throw new IllegalArgumentException("partySize must be positive"); }
}
