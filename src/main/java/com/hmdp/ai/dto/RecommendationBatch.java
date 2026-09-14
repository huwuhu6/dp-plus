package com.hmdp.ai.dto;
import lombok.Data;
import java.util.ArrayList;
import java.util.List;
@Data public class RecommendationBatch { private Long decisionSessionId; private String batchId; private List<RecommendationCandidateRef> candidates = new ArrayList<>(); }
