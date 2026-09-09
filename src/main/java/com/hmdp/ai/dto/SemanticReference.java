package com.hmdp.ai.dto;

import lombok.Data;

/** Structured reference intent. Java resolves it to real entity IDs later. */
@Data
public class SemanticReference {
    public enum Scope { LATEST, EARLIEST, FOCUSED, UNSPECIFIED }

    private String id;
    private Scope scope = Scope.UNSPECIFIED;
    private Integer ordinal;
    private String surface;
    private Integer start;
    private Integer end;
    private String qualifier;
    private Boolean deictic;
    private SemanticEvidence evidence;
}
