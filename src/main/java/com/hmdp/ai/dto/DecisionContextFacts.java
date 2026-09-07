package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Deterministic facts used to render a decision-context answer. */
@Data
public class DecisionContextFacts {
    private Long decisionSessionId;
    private Long shopId;
    private String shopName;
    private Long avgPrice;
    private Double distanceKm;
    private List<String> matchedReasons = new ArrayList<String>();
    private List<String> evidence = new ArrayList<String>();
    private String constraintKey;
    private String constraintValue;
    private ConstraintSource source;
    private DecisionConstraints currentCriteria;
    private Map<String, ConstraintSource> constraintSources;
}
