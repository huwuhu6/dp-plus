package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DecisionConstraints {
    private String cuisine = "";
    private Integer budgetPerPerson = -1;
    private Double radiusKm = -1D;
    private Boolean nearby = false;
    private String arrivalTime = "";
    private String occasion = "";
    private Boolean quiet = false;
    private Boolean avoidQueue = false;
    private List<String> hardConstraints = new ArrayList<>();
    private List<String> softPreferences = new ArrayList<>();
    private List<String> missingInformation = new ArrayList<>();
}
