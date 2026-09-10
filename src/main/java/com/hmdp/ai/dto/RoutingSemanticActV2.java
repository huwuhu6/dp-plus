package com.hmdp.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

/** Linguistic act for compact routing fusion; not a ChatProcessingAction. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RoutingSemanticActV2 {
    public enum Type {
        REQUEST_RECOMMENDATION,
        MUTATE_CRITERIA,
        EXPLORE_ALTERNATIVE,
        CHITCHAT_OR_UNKNOWN
    }

    private Type type;
    private String evidenceText;
}
