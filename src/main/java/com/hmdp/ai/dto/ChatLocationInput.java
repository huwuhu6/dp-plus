package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class ChatLocationInput {
    private Double latitude;
    private Double longitude;
    private Double accuracyMeters;
    private String source;
}
