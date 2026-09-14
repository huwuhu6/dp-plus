package com.hmdp.ai.v2.semantic;

/** Partial user delta. Unlike canonical DiningCriteria, null here has the single meaning “untouched this turn”. */
public record DiningCriteriaPatch(DiningCriteria.LocationCriteria location, DiningCriteria.CuisineCriteria cuisine,
                                  DiningCriteria.BudgetCriteria budget, DiningCriteria.DistanceCriteria distance,
                                  DiningCriteria.DiningTimeCriteria diningTime,
                                  DiningCriteria.SemanticPreferences semanticPreferences) { }
