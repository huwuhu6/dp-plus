package com.hmdp.ai.dto;

import lombok.Data;

@Data
public class DecisionOption {
    private String id;
    private String label;

    public DecisionOption(String id, String label) {
        this.id = id;
        this.label = label;
    }
}
