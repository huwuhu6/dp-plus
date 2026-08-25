package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.ResolvedLocationCandidate;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@Service
public class AmapMcpLocationResolutionService {
    private static final Logger log = LoggerFactory.getLogger(AmapMcpLocationResolutionService.class);

    @Value("${ai.location.mcp.enabled:false}")
    private boolean enabled;
    @Value("${ai.location.mcp.tool-timeout-ms:800}")
    private long toolTimeoutMs = 800L;
    @Autowired(required = false)
    private List<McpSyncClient> mcpClients = Collections.emptyList();
    @Resource private ObjectMapper objectMapper;

    public boolean isAvailable() {
        return enabled && !mcpClients.isEmpty();
    }

    public List<ResolvedLocationCandidate> resolve(String placeText) {
        if (!isAvailable() || placeText == null || placeText.trim().isEmpty()) return Collections.emptyList();
        String query = placeText.trim();
        long startedAt = System.currentTimeMillis();
        try {
            Map<String, Object> arguments = new LinkedHashMap<>();
            arguments.put("address", query);
            McpSchema.CallToolResult result = callMapsGeo(arguments);
            if (Boolean.TRUE.equals(result.isError())) {
                log.warn("[AI][location] event=MCP_GEO_FAILURE query={} durationMs={} reason=tool_error",
                        compact(query), System.currentTimeMillis() - startedAt);
                return Collections.emptyList();
            }
            List<ResolvedLocationCandidate> candidates = parseCandidates(result, query);
            log.info("[AI][location] event=MCP_GEO_SUCCESS query={} candidates={} durationMs={}",
                    compact(query), candidates.size(), System.currentTimeMillis() - startedAt);
            return candidates;
        } catch (java.util.concurrent.TimeoutException e) {
            log.warn("[AI][location] event=MCP_GEO_TIMEOUT query={} durationMs={} timeoutMs={}", compact(query),
                    System.currentTimeMillis() - startedAt, toolTimeoutMs);
            return Collections.emptyList();
        } catch (Exception e) {
            log.warn("[AI][location] event=MCP_GEO_FAILURE query={} durationMs={} errorType={}",
                    compact(query), System.currentTimeMillis() - startedAt, e.getClass().getSimpleName());
            return Collections.emptyList();
        }
    }

    private McpSchema.CallToolResult callMapsGeo(Map<String, Object> arguments) throws Exception {
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            return CompletableFuture.supplyAsync(() -> mcpClients.get(0)
                    .callTool(new McpSchema.CallToolRequest("maps_geo", arguments)), executor)
                    .get(toolTimeoutMs, TimeUnit.MILLISECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private List<ResolvedLocationCandidate> parseCandidates(McpSchema.CallToolResult result, String query) throws Exception {
        List<ResolvedLocationCandidate> candidates = new ArrayList<>();
        for (McpSchema.Content content : result.content()) {
            if (!(content instanceof McpSchema.TextContent textContent)) continue;
            JsonNode root = objectMapper.readTree(textContent.text());
            collectCandidates(root, query, candidates);
        }
        return candidates;
    }

    private void collectCandidates(JsonNode node, String query, List<ResolvedLocationCandidate> candidates) {
        if (node == null) return;
        if (node.isArray()) {
            node.forEach(item -> collectCandidates(item, query, candidates));
            return;
        }
        if (!node.isObject()) return;
        ResolvedLocationCandidate candidate = candidateFrom(node, query);
        if (candidate != null && candidates.stream().noneMatch(existing -> sameCoordinates(existing, candidate))) {
            candidates.add(candidate);
        }
        node.elements().forEachRemaining(item -> collectCandidates(item, query, candidates));
    }

    private ResolvedLocationCandidate candidateFrom(JsonNode node, String query) {
        String location = node.path("location").asText("");
        String[] coordinates = location.split(",");
        if (coordinates.length != 2) return null;
        try {
            ResolvedLocationCandidate candidate = new ResolvedLocationCandidate();
            candidate.setLongitude(Double.valueOf(coordinates[0].trim()));
            candidate.setLatitude(Double.valueOf(coordinates[1].trim()));
            candidate.setProvince(text(node, "province"));
            candidate.setCity(text(node, "city"));
            candidate.setDistrict(text(node, "district"));
            String label = firstText(node, "formatted_address", "address", "name");
            candidate.setLabel(label == null ? query : label);
            candidate.setSource("AMAP_MCP");
            return candidate;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String firstText(JsonNode node, String... names) {
        for (String name : names) {
            String value = text(node, name);
            if (value != null) return value;
        }
        return null;
    }

    private String text(JsonNode node, String name) {
        String value = node.path(name).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private boolean sameCoordinates(ResolvedLocationCandidate left, ResolvedLocationCandidate right) {
        return left.getLatitude().equals(right.getLatitude()) && left.getLongitude().equals(right.getLongitude());
    }

    private String compact(String value) {
        return value.length() > 120 ? value.substring(0, 120) + "..." : value;
    }
}
