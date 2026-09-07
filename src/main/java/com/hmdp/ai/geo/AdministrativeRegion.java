package com.hmdp.ai.geo;

import lombok.Data;

@Data
public class AdministrativeRegion {
    private String adcode;
    private AdministrativeLevel level;
    private String name;
    private String province;
    private String city;
    private String district;
    private String parentAdcode;
}
