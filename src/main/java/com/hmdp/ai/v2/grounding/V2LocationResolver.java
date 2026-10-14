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
    public static String personalReference(String user){
        var m=java.util.regex.Pattern.compile("(?:我(?:们)?的?)?(公司|单位|家|学校|住处)(?:附近|周边|周围)").matcher(user);
        return m.find()?m.group(1):null;
    }
    private final LocationResolutionProvider locations;

    @Autowired
    public V2LocationResolver(@Autowired(required = false) LocationResolutionProvider locations) {
        this.locations = locations;
    }

    public Resolution resolve(DiningCriteria criteria, ChatLocationInput device, SearchAnchor previous) {
        return resolve(criteria, device, previous, true);
    }

    /** Location-change intent comes from typed semantics; criteria alone cannot distinguish inherited location from a new one. */
    public Resolution resolve(DiningCriteria criteria, ChatLocationInput device, SearchAnchor previous, boolean locationChanged) {
        DiningCriteria.LocationCriteria requested = criteria == null ? null : criteria.location();
        boolean explicitArea = hasText(requested == null ? null : requested.city())
                || hasText(requested == null ? null : requested.district());
        String poi = requested == null ? null : requested.poi();
        boolean personal=hasText(poi) && poi.matches("(?:我(?:们)?的?)?(?:公司|单位|家|学校|住处)(?:附近|周边|周围)?");

        if (!locationChanged && !validCoordinates(device) && previous != null
                && (!personal || previous.source()==SearchAnchor.AnchorSource.PERSONAL_LOCATION_PROFILE)
                && (!requiresDistance(criteria) || validCoordinates(previous))) return new Resolution.Resolved(previous);

        boolean vagueNear = poi != null && poi.matches("(?i)附近|周边|周围|这里|这附近") || requested!=null && requested.nearby() && !hasText(poi);
        if (!personal && hasText(poi) && !vagueNear && previous != null && previous.source() == SearchAnchor.AnchorSource.POI
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
            // 公共POI唯一不等于用户的公司/家。只有用户专属地点资料提供者能绑定此类指代。
            if(personal && !"PERSONAL_LOCATION_PROFILE".equals(candidate.getSource()))
                return new Resolution.NeedsClarification("请提供“"+poi+"”的具体名称或地址；不能把任意公共地点当作你的公司或住处。");
            if (candidate.getLatitude() == null || candidate.getLongitude() == null || !Double.isFinite(candidate.getLatitude())
                    || !Double.isFinite(candidate.getLongitude()) || Math.abs(candidate.getLatitude())>90 || Math.abs(candidate.getLongitude())>180)
                return new Resolution.NeedsClarification("无法可靠确定“" + poi + "”的位置，请提供更明确的地点。");
            return new Resolution.Resolved(new SearchAnchor(candidate.getPoiId(),
                    firstText(candidate.getCanonicalName(), candidate.getLabel(), poi), candidate.getLatitude(),
                    candidate.getLongitude(), firstText(candidate.getProvince(), null, null),
                    firstText(candidate.getCity(), requested.city(), null),
                    firstText(candidate.getDistrict(), requested.district(), null), personal?SearchAnchor.AnchorSource.PERSONAL_LOCATION_PROFILE:SearchAnchor.AnchorSource.POI));
        }

        if (explicitArea) {
            if (hasText(requested.district()) && !verifiedAdministrativeDistrict(requested, device))
                return new Resolution.NeedsClarification("无法验证“" + requested.district() + "”是唯一的行政区，请补充城市或明确地点。");
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
        return criteria != null && (criteria.distance() != null && criteria.distance().hardMaxKm() != null
                || criteria.location()!=null && criteria.location().nearby());
    }
    /** A model label is not an administrative identity.  Trust a district only after the location provider resolves it uniquely. */
    private boolean verifiedAdministrativeDistrict(DiningCriteria.LocationCriteria requested, ChatLocationInput device) {
        if (locations == null || !locations.isAvailable()) return false;
        LocationResolutionContext context = new LocationResolutionContext();
        context.setActiveCity(requested.city());
        if (validCoordinates(device)) {
            context.setDeviceLatitude(device.getLatitude());
            context.setDeviceLongitude(device.getLongitude());
        }
        String query = hasText(requested.city()) ? requested.city() + requested.district() : requested.district();
        List<ResolvedLocationCandidate> candidates = locations.resolve(new LocationResolutionRequest(query, context, "DISTRICT"));
        return candidates != null && candidates.size() == 1 && hasText(candidates.getFirst().getDistrict())
                && candidates.getFirst().getDistrict().equals(requested.district());
    }
    private boolean validCoordinates(ChatLocationInput input) {
        return input != null && input.getLatitude() != null && input.getLongitude() != null
                && input.getLatitude() >= -90D && input.getLatitude() <= 90D
                && input.getLongitude() >= -180D && input.getLongitude() <= 180D;
    }
    private boolean validCoordinates(SearchAnchor anchor) { return anchor != null && anchor.latitude() != null && anchor.longitude() != null
            && Double.isFinite(anchor.latitude()) && Double.isFinite(anchor.longitude()) && Math.abs(anchor.latitude())<=90 && Math.abs(anchor.longitude())<=180; }
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
