package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.ResolvedLocationCandidate;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AmapMcpLocationResolutionServiceTest {

    @Test
    void parsesCoordinatesReturnedByMapsGeo() {
        McpSyncClient client = mock(McpSyncClient.class);
        String body = "{\"geocodes\":[{\"formatted_address\":\"福建省福州市鼓楼区\","
                + "\"province\":\"福建省\",\"city\":\"福州市\",\"district\":\"鼓楼区\","
                + "\"location\":\"119.2998,26.0871\"}]}";
        when(client.callTool(any())).thenReturn(new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent(body)), false));

        AmapMcpLocationResolutionService service = new AmapMcpLocationResolutionService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "mcpClients", List.of(client));
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());

        List<ResolvedLocationCandidate> result = service.resolve("福州鼓楼");

        assertEquals(1, result.size());
        assertEquals("福建省福州市鼓楼区", result.get(0).getLabel());
        assertEquals(26.0871D, result.get(0).getLatitude());
        assertEquals(119.2998D, result.get(0).getLongitude());
        assertEquals("AMAP_MCP", result.get(0).getSource());
    }

    @Test
    void returnsEmptyCandidatesWhenMcpExceedsToolTimeout() {
        McpSyncClient client = mock(McpSyncClient.class);
        when(client.callTool(any())).thenAnswer(invocation -> {
            Thread.sleep(200L);
            return new McpSchema.CallToolResult(List.of(), false);
        });
        AmapMcpLocationResolutionService service = new AmapMcpLocationResolutionService();
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "toolTimeoutMs", 30L);
        ReflectionTestUtils.setField(service, "mcpClients", List.of(client));
        ReflectionTestUtils.setField(service, "objectMapper", new ObjectMapper());
        long startedAt = System.currentTimeMillis();

        List<ResolvedLocationCandidate> result = service.resolve("福州鼓楼");

        assertTrue(result.isEmpty());
        assertTrue(System.currentTimeMillis() - startedAt < 180L);
    }
}
