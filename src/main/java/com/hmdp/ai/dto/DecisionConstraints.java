package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Data
public class DecisionConstraints {
    /** User's explicit search destination. It is never a substitute for device location. */
    private String targetCity = "";
    private String targetArea = "";
    /** Explicit destination, current device, or unspecified location intent. */
    private String locationIntent = "UNSPECIFIED";
    /** Restaurant name or cuisine phrase after geographic slots have been consumed structurally. */
    private String keyword = "";
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
    private Set<String> lockedConstraints = new HashSet<>();
    /** Constraint fields the user explicitly abandons (e.g. "看看有没有别的吃的" clears cuisine/keyword). */
    private List<String> clearedFields = new ArrayList<>();
}
