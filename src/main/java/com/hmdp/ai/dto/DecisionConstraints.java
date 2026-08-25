package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class DecisionConstraints {
    /** Explicit destination city requested by the user, independent from device location. */
    private String targetCity = "";
    /** Explicit destination area, district, business circle, or landmark requested by the user. */
    private String targetArea = "";
    /** Restaurant name or core search term after geography is separated from the request. */
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
}
