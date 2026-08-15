package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class DecisionFollowUpRequest {
    private String selectedOptionId;
    private String message;
    private Double latitude;
    private Double longitude;
}
