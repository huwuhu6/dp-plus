package com.hmdp.ai.dto;

import lombok.Data;

/** Java-owned grounding result for an evidenceText mention. */
@Data
public class ResolvedEvidence {
    public enum Status { RESOLVED, AMBIGUOUS, NOT_FOUND, MISSING }

    private String text;
    private Integer start;
    private Integer end;
    private Status status;

    public static ResolvedEvidence of(String text, Integer start, Integer end, Status status) {
        ResolvedEvidence value = new ResolvedEvidence();
        value.setText(text);
        value.setStart(start);
        value.setEnd(end);
        value.setStatus(status);
        return value;
    }
}
