package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Auditable result-policy outcome. The client can explain exactly which system
 * default changed, while explicit user constraints remain untouched.
 */
@Data
public class RelaxationInfo {
    private String outcome = "STRICT_MATCH";
    private Integer strictCandidateCount = 0;
    private Integer relaxedCandidateCount = 0;
    private Boolean automatic = false;
    private String reason;
    private List<String> preservedHardConstraints = new ArrayList<String>();
    private List<String> appliedChanges = new ArrayList<String>();
}
