package com.hmdp.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Ephemeral structured understanding for one turn. It is never persisted as
 * Working Memory and contains no shop/session identifiers.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class TurnSemanticIR {
    private String version = "v1";
    private List<SemanticAct> acts = new ArrayList<>();
    private List<SemanticReference> references = new ArrayList<>();
    private List<CriteriaDeltaOperation> criteriaDelta = new ArrayList<>();
    private LocationExpression locationExpression;
    private DecisionContextSemanticQuery decisionContextQuery;
    private List<ShopFactSemanticQuery> shopFactQueries = new ArrayList<>();
    private List<String> ambiguities = new ArrayList<>();
}
