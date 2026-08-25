package com.hmdp.ai.tool;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ToolExecutionRequest {
    private Integer order;
    private String toolName;
    private String arguments;
    private Long explicitlyReferencedShopId;
}
