package com.hmdp.ai.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class LocalDemoDatasetRequest {
    private Integer shopsPerCity = 300;
    private Long seed = 20260827L;
    private List<City> cities = new ArrayList<City>();

    @Data
    public static class City {
        private String province;
        private String city;
        private List<String> districts = new ArrayList<String>();
        private Double longitude;
        private Double latitude;
    }
}
