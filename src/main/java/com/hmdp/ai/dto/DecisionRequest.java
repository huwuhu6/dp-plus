package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class DecisionRequest {
    private String query;
    private Double latitude;
    private Double longitude;
    private Integer maxCandidates = 3;
}
