package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class AgentConversationResponse {
    private Long sessionId;
    private Integer turnNo;
    private String answer;
    private Long focusedShopId;
    private String focusedShopName;
    private Boolean usedModel;
    private String degradedReason;
    private List<AgentToolTraceItem> toolTrace = new ArrayList<AgentToolTraceItem>();
}
