package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DecisionRecommendation {
    private Long shopId;
    private String shopName;
    private Long avgPrice;
    private Double distanceKm;
    private Double score;
    private Double semanticScore;
    private String address;
    private String openHours;
    private List<String> matchedReasons = new ArrayList<>();
    private List<String> evidence = new ArrayList<>();
}
