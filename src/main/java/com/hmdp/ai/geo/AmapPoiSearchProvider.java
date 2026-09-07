package com.hmdp.ai.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.LocationResolutionContext;
import com.hmdp.ai.dto.LocationResolutionRequest;
import com.hmdp.ai.dto.ResolvedLocationCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * GaoDe WebService POI text search. This is deliberately separate from the
 * MCP geocoder: POI search supplies canonical entity identity for short names
 * such as a university or campus, while MCP remains a fallback for addresses.
 */
@Service
@Profile("!eval")
public class AmapPoiSearchProvider {
    private static final Logger log = LoggerFactory.getLogger(AmapPoiSearchProvider.class);
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${ai.location.amap.enabled:false}") private boolean enabled;
    @Value("${ai.location.amap.api-key:}") private String apiKey;
    @Value("${ai.location.amap.poi-endpoint:https://restapi.amap.com/v5/place/text}") private String endpoint;
    @Value("${ai.location.amap.poi-timeout-ms:1500}") private long timeoutMs = 1500L;

    public AmapPoiSearchProvider() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1500)).build(), new ObjectMapper());
    }

    AmapPoiSearchProvider(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public boolean isAvailable() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    public List<ResolvedLocationCandidate> resolve(LocationResolutionRequest request) {
        if (!isAvailable() || request == null || !hasText(request.getRawText())) return List.of();
        String keywords = request.getRawText().trim();
        LocationResolutionContext context = request.getContext();
        String region = region(context);
        try {
            StringBuilder uri = new StringBuilder(endpoint)
                    .append("?key=").append(encode(apiKey))
                    .append("&keywords=").append(encode(keywords))
                    .append("&page_size=20&show_fields=business");
            if (hasText(region)) {
                uri.append("&region=").append(encode(region)).append("&city_limit=true");
            }
            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(uri.toString()))
                    .timeout(Duration.ofMillis(timeoutMs)).GET().build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) return List.of();
            List<ResolvedLocationCandidate> candidates = parseResponse(response.body(), keywords);
            rankByDeviceDistance(candidates, context);
            log.info("[AI][location] event=AMAP_POI_SUCCESS keywords={} region={} candidates={}",
                    compact(keywords), region, candidates.size());
            return candidates;
        } catch (Exception e) {
            log.warn("[AI][location] event=AMAP_POI_FAILURE keywords={} reason={}",
                    compact(keywords), e.getClass().getSimpleName());
            return List.of();
        }
    }

    List<ResolvedLocationCandidate> parseResponse(String body, String query) {
        try {
            JsonNode root = objectMapper.readTree(body);
            if (!"1".equals(root.path("status").asText())) return List.of();
            List<ResolvedLocationCandidate> result = new ArrayList<>();
            for (JsonNode poi : root.path("pois")) {
                ResolvedLocationCandidate candidate = parseCandidate(poi);
                if (candidate != null) result.add(candidate);
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private ResolvedLocationCandidate parseCandidate(JsonNode poi) {
        String name = text(poi, "name");
        String location = text(poi, "location");
        if (!hasText(name) || !hasText(location)) return null;
        String[] coordinates = location.split(",");
        if (coordinates.length != 2) return null;
        try {
            ResolvedLocationCandidate candidate = new ResolvedLocationCandidate();
            candidate.setPoiId(firstText(poi, "id", "poi_id"));
            candidate.setCanonicalName(name);
            candidate.setLabel(name);
            candidate.setCampusLabel(campusLabel(poi));
            candidate.setLongitude(Double.valueOf(coordinates[0].trim()));
            candidate.setLatitude(Double.valueOf(coordinates[1].trim()));
            candidate.setProvince(firstText(poi, "pname", "province"));
            candidate.setCity(firstText(poi, "cityname", "city"));
            candidate.setDistrict(firstText(poi, "adname", "district"));
            candidate.setSource("AMAP_POI");
            return candidate;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String campusLabel(JsonNode poi) {
        JsonNode business = poi.path("business");
        String value = firstText(business, "business_area", "business_area_name");
        return hasText(value) ? value : null;
    }

    private String region(LocationResolutionContext context) {
        if (context == null) return null;
        if (hasText(context.getActiveCity())) return context.getActiveCity();
        return hasText(context.getActiveProvince()) ? context.getActiveProvince() : null;
    }

    private void rankByDeviceDistance(List<ResolvedLocationCandidate> candidates, LocationResolutionContext context) {
        if (context == null || context.getDeviceLatitude() == null || context.getDeviceLongitude() == null) return;
        double latitude = context.getDeviceLatitude();
        double longitude = context.getDeviceLongitude();
        candidates.sort(Comparator.comparingDouble(item -> distanceSquared(item, latitude, longitude)));
    }

    private double distanceSquared(ResolvedLocationCandidate candidate, double latitude, double longitude) {
        if (candidate.getLatitude() == null || candidate.getLongitude() == null) return Double.MAX_VALUE;
        double lat = candidate.getLatitude() - latitude;
        double lon = candidate.getLongitude() - longitude;
        return lat * lat + lon * lon;
    }

    private String firstText(JsonNode node, String... names) {
        if (node == null) return null;
        for (String name : names) {
            String value = text(node, name);
            if (hasText(value)) return value;
        }
        return null;
    }

    private String text(JsonNode node, String name) {
        String value = node.path(name).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String compact(String value) {
        return value.length() > 80 ? value.substring(0, 80) + "..." : value;
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
