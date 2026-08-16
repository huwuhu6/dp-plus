package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentSessionContext {
    private Integer turnNo = 0;
    private Long focusedShopId;
    private String focusedShopName;
    private List<Long> shownShopIds = new ArrayList<Long>();
    private DecisionRequest decisionRequest;
    private DecisionConstraints decisionConstraints;
}
