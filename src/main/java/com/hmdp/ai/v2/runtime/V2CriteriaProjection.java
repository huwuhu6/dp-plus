package com.hmdp.ai.v2.runtime;

import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.v2.semantic.DiningCriteria;

/** One-way boundary into the still-useful deterministic retrieval infrastructure. */
public final class V2CriteriaProjection {
    private V2CriteriaProjection() { }
    public static DecisionConstraints retrieval(DiningCriteria criteria) {
        DecisionConstraints target = new DecisionConstraints();
        if (criteria == null) return target;
        if (criteria.location() != null) {
            target.setTargetCity(blank(criteria.location().city()));
            target.setTargetDistrict(blank(criteria.location().district()));
            target.setTargetArea(blank(criteria.location().poi()));
            target.setLocationIntent((criteria.location().city() == null && criteria.location().district() == null
                    && criteria.location().poi() == null) ? "UNSPECIFIED" : "EXPLICIT_TARGET");
        }
        if (criteria.cuisine() != null) {
            target.setCuisine(blank(criteria.cuisine().include()));
            target.setExcludedCuisines(criteria.cuisine().exclude());
        }
        if (criteria.budget() != null) {
            target.setBudgetPerPerson(criteria.budget().hardMax() != null ? criteria.budget().hardMax().intValue()
                    : criteria.budget().softTarget() == null ? -1 : criteria.budget().softTarget().intValue());
        }
        if (criteria.distance() != null && criteria.distance().hardMaxKm() != null)
            target.setRadiusKm(criteria.distance().hardMaxKm().doubleValue());
        if (criteria.diningTime() != null) target.setArrivalTime(blank(criteria.diningTime().arrivalTime()));
        if (criteria.preferences() != null) target.setPreferences(criteria.preferences().values().stream()
                .map(DiningCriteria.SemanticPreference::criterion).toList());
        return target;
    }
    private static String blank(String value) { return value == null ? "" : value; }
}
