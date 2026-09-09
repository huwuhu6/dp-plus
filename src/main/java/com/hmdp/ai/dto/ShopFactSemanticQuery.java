package com.hmdp.ai.dto;

import lombok.Data;

/** Request-scoped shop fact query; tool selection remains Java-owned. */
@Data
public class ShopFactSemanticQuery {
    private ShopFactQueryType type;
    private String referenceId;
    private SemanticEvidence evidence;
}
