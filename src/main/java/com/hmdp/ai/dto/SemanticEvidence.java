package com.hmdp.ai.dto;

import lombok.Data;

/** Evidence span returned by the structured semantic interpreter. */
@Data
public class SemanticEvidence {
    private String text;
    private Integer start;
    private Integer end;
}
