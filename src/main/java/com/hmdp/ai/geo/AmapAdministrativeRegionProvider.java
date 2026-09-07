package com.hmdp.ai.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** GaoDe administrative WebService fallback. It is deliberately not the MCP POI resolver. */
@Service
public class AmapAdministrativeRegionProvider implements AdministrativeRegionProvider {
    private static final Logger log = LoggerFactory.getLogger(AmapAdministrativeRegionProvider.class);
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    @Value("${ai.location.amap.enabled:false}") private boolean enabled;
    @Value("${ai.location.amap.api-key:}") private String apiKey;
    @Value("${ai.location.amap.endpoint:https://restapi.amap.com/v3/config/district}") private String endpoint;
    @Value("${ai.location.amap.timeout-ms:800}") private long timeoutMs = 800L;
    @Value("${ai.location.amap.cache-ttl-ms:86400000}") private long cacheTtlMs = 86_400_000L;

    public AmapAdministrativeRegionProvider() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build(), new ObjectMapper());
    }

    AmapAdministrativeRegionProvider(HttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<AdministrativeRegion> resolve(String keyword, String parentAdcode) {
        if (!enabled || apiKey == null || apiKey.isBlank() || keyword == null || keyword.isBlank()) return List.of();
        String cacheKey = (parentAdcode == null ? "" : parentAdcode) + ":" + keyword.trim().toLowerCase();
        CacheEntry cached = cache.get(cacheKey);
        if (cached != null && cached.expiresAt() > System.currentTimeMillis()) return cached.regions();
        try {
            String query = endpoint + "?key=" + encode(apiKey) + "&keywords=" + encode(keyword.trim())
                    + "&subdistrict=0&extensions=base";
            if (parentAdcode != null && !parentAdcode.isBlank()) query += "&filter=" + encode(parentAdcode);
            HttpRequest request = HttpRequest.newBuilder(URI.create(query))
                    .timeout(Duration.ofMillis(timeoutMs)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) return List.of();
            List<AdministrativeRegion> regions = parse(objectMapper.readTree(response.body()));
            cache.put(cacheKey, new CacheEntry(regions, System.currentTimeMillis() + Math.max(0L, cacheTtlMs)));
            return regions;
        } catch (Exception e) {
            log.warn("[AI][location] event=AMAP_ADMIN_FAILURE keyword={} reason={}", keyword,
                    e.getClass().getSimpleName());
            return List.of();
        }
    }

    private record CacheEntry(List<AdministrativeRegion> regions, long expiresAt) {}

    private List<AdministrativeRegion> parse(JsonNode root) {
        if (!"1".equals(root.path("status").asText())) return List.of();
        List<AdministrativeRegion> result = new ArrayList<>();
        for (JsonNode node : root.path("districts")) {
            AdministrativeRegion region = new AdministrativeRegion();
            region.setAdcode(text(node, "adcode"));
            region.setName(text(node, "name"));
            region.setParentAdcode(text(node, "parent"));
            String level = text(node, "level");
            region.setLevel(level == null ? null : toLevel(level));
            region.setProvince(text(node, "province"));
            region.setCity(text(node, "city"));
            region.setDistrict(region.getLevel() == AdministrativeLevel.DISTRICT ? region.getName() : text(node, "district"));
            if (region.getAdcode() != null && region.getName() != null) result.add(region);
        }
        return result;
    }

    private AdministrativeLevel toLevel(String value) {
        if (value.contains("省") || value.contains("自治区")) return AdministrativeLevel.PROVINCE;
        if (value.contains("市") || value.contains("州") || value.contains("盟")) return AdministrativeLevel.CITY;
        return AdministrativeLevel.DISTRICT;
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
