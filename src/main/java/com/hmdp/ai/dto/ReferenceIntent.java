package com.hmdp.ai.dto;

import lombok.Data;

/**
 * Structured reference extracted from one user message.  The extractor owns the
 * natural-language vocabulary; downstream resolvers only consume this value.
 */
@Data
public class ReferenceIntent {
    public enum Scope { LATEST, EARLIEST, FOCUSED }

    private Scope scope = Scope.LATEST;
    private Integer ordinal;
    /** The exact user span (end is exclusive) used to bind multiple references. */
    private String surface;
    private Integer start;
    private Integer end;
    /** Optional human-facing descriptor, for example "日本料理" or "烧烤". */
    private String qualifier;
    /** True when the expression is deictic (这个/这家/那个), even if qualified. */
    private boolean deictic;
    /** Model-assigned association for a criteria critique in a compound turn. */
    private boolean mutationAnchor;

    public ReferenceIntent() {
    }

    public ReferenceIntent(Scope scope, Integer ordinal, String surface, Integer start, Integer end) {
        this.scope = scope == null ? Scope.LATEST : scope;
        this.ordinal = ordinal;
        this.surface = surface;
        this.start = start;
        this.end = end;
    }
}
