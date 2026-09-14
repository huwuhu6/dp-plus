package com.hmdp.ai.v2.semantic;

/** A deliberately closed set prevents a Field/Object EAV model from leaking into the domain. */
public sealed interface RequirementChange permits RequirementChange.CriteriaPatch, RequirementChange.RelativePreference,
        RequirementChange.RelaxationAuthorization, RequirementChange.RequirementLock {
    record CriteriaPatch(DiningCriteria criteria, boolean clearsBudget) implements RequirementChange { }
    /** “便宜一点” is relative user intent, never an invented absolute budget. */
    record RelativePreference(DiningCriteria.PreferenceDimension dimension, Direction direction) implements RequirementChange { }
    record RelaxationAuthorization(DiningCriteria.PreferenceDimension dimension) implements RequirementChange { }
    record RequirementLock(DiningCriteria.PreferenceDimension dimension) implements RequirementChange { }
    enum Direction { LOWER, HIGHER }
}
