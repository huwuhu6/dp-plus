package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class CriteriaMergeResult {
    private DecisionConstraints constraints;
    private List<String> inherited = new ArrayList<String>();
    private List<String> replaced = new ArrayList<String>();
    private List<String> appended = new ArrayList<String>();
    private List<String> cleared = new ArrayList<String>();
    private List<String> invalidated = new ArrayList<String>();
}
