package com.hmdp.ai.dto;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;

@Data
public class RecommendationCandidateRef {
    private Long shopId;
    private String shopName;
    private Long pricePerPerson;
    private Double distanceKm;
    private String cuisine;
    private List<String> referenceTags = new ArrayList<>();
}
