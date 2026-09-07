package com.hmdp.ai.geo;

import java.util.ArrayList;
import java.util.List;

public record AdministrativeResolution(Status status, List<AdministrativeRegion> candidates) {
    public enum Status { RESOLVED, AMBIGUOUS, NOT_FOUND }

    public AdministrativeResolution {
        candidates = candidates == null ? new ArrayList<>() : List.copyOf(candidates);
    }

    public static AdministrativeResolution notFound() {
        return new AdministrativeResolution(Status.NOT_FOUND, List.of());
    }
}
