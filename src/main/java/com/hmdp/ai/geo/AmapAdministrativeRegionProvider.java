package com.hmdp.ai.geo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
    private final AdministrativeRegionRepository repository;
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    @Value("${ai.location.amap.enabled:false}") private boolean enabled;
    @Value("${ai.location.amap.api-key:}") private String apiKey;
    @Value("${ai.location.amap.endpoint:https://restapi.amap.com/v3/config/district}") private String endpoint;
    @Value("${ai.location.amap.timeout-ms:800}") private long timeoutMs = 800L;
    @Value("${ai.location.amap.cache-ttl-ms:86400000}") private long cacheTtlMs = 86_400_000L;

    public AmapAdministrativeRegionProvider() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build(), new ObjectMapper(),
                new ClasspathAdministrativeRegionRepository());
    }

    @Autowired
    public AmapAdministrativeRegionProvider(AdministrativeRegionRepository repository) {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(800)).build(), new ObjectMapper(), repository);
    }

    AmapAdministrativeRegionProvider(HttpClient httpClient, ObjectMapper objectMapper,
                                     AdministrativeRegionRepository repository) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.repository = repository;
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
            HttpRequest request = HttpRequest.newBuilder(URI.create(query))
                    .timeout(Duration.ofMillis(timeoutMs)).GET().build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) return List.of();
            List<AdministrativeRegion> regions = parseResponse(response.body());
            cache.put(cacheKey, new CacheEntry(regions, System.currentTimeMillis() + Math.max(0L, cacheTtlMs)));
            return regions;
        } catch (Exception e) {
            log.warn("[AI][location] event=AMAP_ADMIN_FAILURE keyword={} reason={}", keyword,
                    e.getClass().getSimpleName());
            return List.of();
        }
    }

    private record CacheEntry(List<AdministrativeRegion> regions, long expiresAt) {}

    List<AdministrativeRegion> parseResponse(String body) {
        try {
            return parse(objectMapper.readTree(body));
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<AdministrativeRegion> parse(JsonNode root) {
        if (!"1".equals(root.path("status").asText())) return List.of();
        List<AdministrativeRegion> result = new ArrayList<>();
        for (JsonNode node : root.path("districts")) {
            collect(node, result);
        }
        return result;
    }

    private void collect(JsonNode node, List<AdministrativeRegion> result) {
        String adcode = text(node, "adcode");
        String name = text(node, "name");
        AdministrativeLevel level = toLevel(text(node, "level"));
        if (adcode != null && name != null && level != null) {
            AdministrativeRegion region = repository.findByAdcode(adcode).map(this::copy).orElseGet(AdministrativeRegion::new);
            region.setAdcode(adcode);
            region.setName(name);
            region.setLevel(level);
            if (level == AdministrativeLevel.DISTRICT && !hasText(region.getDistrict())) region.setDistrict(name);
            result.add(region);
        }
        for (JsonNode child : node.path("districts")) collect(child, result);
    }

    private AdministrativeLevel toLevel(String value) {
        if (value == null) return null;
        return switch (value.toLowerCase()) {
            case "province" -> AdministrativeLevel.PROVINCE;
            case "city" -> AdministrativeLevel.CITY;
            case "district" -> AdministrativeLevel.DISTRICT;
            default -> null;
        };
    }

    private String text(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private AdministrativeRegion copy(AdministrativeRegion source) {
        AdministrativeRegion copy = new AdministrativeRegion();
        copy.setAdcode(source.getAdcode()); copy.setLevel(source.getLevel()); copy.setName(source.getName());
        copy.setProvince(source.getProvince()); copy.setCity(source.getCity()); copy.setDistrict(source.getDistrict());
        copy.setParentAdcode(source.getParentAdcode());
        return copy;
    }

    private boolean hasText(String value) { return value != null && !value.isBlank(); }
}
