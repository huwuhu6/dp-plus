package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class ShopReviewUpsertRequest {
    private Long shopId;
    private String sourceType;
    private String sourceKey;
    private Long userId;
    private Integer rating;
    private String content;
}
