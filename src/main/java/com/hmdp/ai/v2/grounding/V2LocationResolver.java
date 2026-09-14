package com.hmdp.ai.v2.grounding;

import com.hmdp.ai.dto.ChatLocationInput;
import com.hmdp.ai.dto.LocationResolutionContext;
import com.hmdp.ai.dto.LocationResolutionRequest;
import com.hmdp.ai.dto.ResolvedLocationCandidate;
import com.hmdp.ai.service.LocationResolutionProvider;
import com.hmdp.ai.v2.plan.SearchAnchor;
import com.hmdp.ai.v2.semantic.DiningCriteria;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

/** Resolves execution-only search anchors; user-authored location criteria remain untouched. */
@Component
public class V2LocationResolver {
    private final LocationResolutionProvider locations;

    @Autowired
    public V2LocationResolver(@Autowired(required = false) LocationResolutionProvider locations) {
        this.locations = locations;
    }

    public Resolution resolve(DiningCriteria criteria, ChatLocationInput device, SearchAnchor previous) {
        DiningCriteria.LocationCriteria requested = criteria == null ? null : criteria.location();
        boolean explicitArea = hasText(requested == null ? null : requested.city())
                || hasText(requested == null ? null : requested.district());
        String poi = requested == null ? null : requested.poi();

        boolean vagueNear = poi != null && poi.matches("(?i)附近|周边|周围|这里|这附近");
        if (hasText(poi) && !vagueNear && previous != null && previous.source() == SearchAnchor.AnchorSource.POI
                && previous.canonicalName() != null && (previous.canonicalName().contains(poi) || poi.contains(previous.canonicalName())))
            return new Resolution.Resolved(previous);

        if (hasText(poi) && !vagueNear) {
            if (locations == null || !locations.isAvailable())
                return new Resolution.NeedsClarification("暂时无法定位“" + poi + "”，请补充城市或更明确的地点。");
            LocationResolutionContext context = new LocationResolutionContext();
            context.setActiveCity(requested.city());
            context.setActiveDistrict(requested.district());
            if (validCoordinates(device)) {
                context.setDeviceLatitude(device.getLatitude());
                context.setDeviceLongitude(device.getLongitude());
            }
            List<ResolvedLocationCandidate> candidates = locations.resolve(new LocationResolutionRequest(poi, context, "POI"));
            if (candidates == null || candidates.isEmpty())
                return new Resolution.NeedsClarification("没有找到“" + poi + "”对应的地点，请补充城市或更明确的名称。");
            if (candidates.size() != 1)
                return new Resolution.NeedsClarification("“" + poi + "”对应多个地点，请补充城市、区县或完整地点名。");
            ResolvedLocationCandidate candidate = candidates.getFirst();
            if (candidate.getLatitude() == null || candidate.getLongitude() == null)
                return new Resolution.NeedsClarification("无法可靠确定“" + poi + "”的位置，请提供更明确的地点。");
            return new Resolution.Resolved(new SearchAnchor(candidate.getPoiId(),
                    firstText(candidate.getCanonicalName(), candidate.getLabel(), poi), candidate.getLatitude(),
                    candidate.getLongitude(), firstText(candidate.getProvince(), null, null),
                    firstText(candidate.getCity(), requested.city(), null),
                    firstText(candidate.getDistrict(), requested.district(), null), SearchAnchor.AnchorSource.POI));
        }

        if (explicitArea) {
            // An administrative scope is sufficient for city/district search; no guessed center point is created.
            if (validCoordinates(device))
                return new Resolution.Resolved(new SearchAnchor(null, "当前位置", device.getLatitude(), device.getLongitude(),
                        null, requested.city(), requested.district(), SearchAnchor.AnchorSource.DEVICE));
            if (requiresDistance(criteria))
                return new Resolution.NeedsClarification("距离条件需要可靠的当前位置或明确地点，请先提供定位或地点名称。");
            return new Resolution.Resolved(new SearchAnchor(null, null, null, null, null,
                    requested.city(), requested.district(), SearchAnchor.AnchorSource.NAMED_LOCATION));
        }

        if (validCoordinates(device)) {
            return new Resolution.Resolved(new SearchAnchor(null, "当前位置", device.getLatitude(), device.getLongitude(),
                    null, null, null, SearchAnchor.AnchorSource.DEVICE));
        }

        if (requiresDistance(criteria) || vagueNear)
            return new Resolution.NeedsClarification("距离条件需要可靠的当前位置或明确地点，请先提供定位或地点名称。");
        return new Resolution.Resolved(previous);
    }

    private boolean requiresDistance(DiningCriteria criteria) {
        return criteria != null && criteria.distance() != null && criteria.distance().hardMaxKm() != null;
    }
    private boolean validCoordinates(ChatLocationInput input) {
        return input != null && input.getLatitude() != null && input.getLongitude() != null
                && input.getLatitude() >= -90D && input.getLatitude() <= 90D
                && input.getLongitude() >= -180D && input.getLongitude() <= 180D;
    }
    private boolean hasText(String value) { return value != null && !value.isBlank(); }
    private String firstText(String first, String second, String fallback) {
        if (hasText(first)) return first;
        if (hasText(second)) return second;
        return fallback;
    }

    public sealed interface Resolution permits Resolution.Resolved, Resolution.NeedsClarification {
        record Resolved(SearchAnchor anchor) implements Resolution { }
        record NeedsClarification(String message) implements Resolution { }
    }
}
