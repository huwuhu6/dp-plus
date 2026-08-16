package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class ResolvedLocationCandidate {
    private String label;
    private String province;
    private String city;
    private String district;
    private Double latitude;
    private Double longitude;
    private String source;
}
