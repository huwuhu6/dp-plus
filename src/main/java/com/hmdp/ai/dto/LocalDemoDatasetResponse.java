package com.hmdp.ai.dto;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class LocalDemoDatasetResponse {
    private int requestedShops;
    private int insertedShops;
    private int updatedShops;
    private int profileCount;
    private int reviewCount;
    private Map<String, Integer> shopsByCity = new LinkedHashMap<String, Integer>();
}
