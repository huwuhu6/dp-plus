package com.hmdp.ai.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ConversationLocationSlot {
    private String status = "MISSING";
    private Double latitude;
    private Double longitude;
    private String province;
    private String city;
    private String district;
    private Double accuracyMeters;
    private String source;
    private LocalDateTime capturedAt;
    private LocalDateTime expiresAt;
}
