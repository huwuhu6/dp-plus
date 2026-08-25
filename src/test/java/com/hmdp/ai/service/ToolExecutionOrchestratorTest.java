package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.ai.tool.AgentToolRegistry;
import com.hmdp.ai.tool.AgentToolResult;
import com.hmdp.ai.tool.BaseAgentTool;
import com.hmdp.ai.tool.ToolExecutionRequest;
import com.hmdp.ai.tool.ToolExecutionResult;
import com.hmdp.ai.tool.ToolResultCompressor;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolExecutionOrchestratorTest {
    @Test
    void runsIndependentToolsConcurrentlyAndMergesByPlanOrder() {
        AgentToolRegistry registry = mock(AgentToolRegistry.class);
        BaseAgentTool evidence = slowTool("search_shop_evidence", 350);
        BaseAgentTool voucher = slowTool("query_shop_vouchers", 350);
        when(registry.find("search_shop_evidence")).thenReturn(evidence);
        when(registry.find("query_shop_vouchers")).thenReturn(voucher);
        ToolExecutionOrchestrator orchestrator = orchestrator(registry);
        long startedAt = System.currentTimeMillis();

        List<ToolExecutionResult> results = orchestrator.execute(Arrays.asList(
                new ToolExecutionRequest(1, "query_shop_vouchers", "{}", null),
                new ToolExecutionRequest(0, "search_shop_evidence", "{}", null)), new AgentSessionContext());

        long elapsed = System.currentTimeMillis() - startedAt;
        assertEquals("search_shop_evidence", results.get(0).getToolName());
        assertEquals("query_shop_vouchers", results.get(1).getToolName());
        assertTrue(results.stream().allMatch(ToolExecutionResult::isSuccess));
        assertTrue(elapsed < 600, "two 350ms read-only tools should overlap, actual=" + elapsed);
    }

    private ToolExecutionOrchestrator orchestrator(AgentToolRegistry registry) {
        ObjectMapper objectMapper = new ObjectMapper();
        ToolResultCompressor compressor = new ToolResultCompressor();
        ReflectionTestUtils.setField(compressor, "objectMapper", objectMapper);
        AiProperties properties = new AiProperties();
        properties.setToolExecutionTimeoutMs(1000);
        ToolExecutionOrchestrator result = new ToolExecutionOrchestrator();
        ReflectionTestUtils.setField(result, "toolRegistry", registry);
        ReflectionTestUtils.setField(result, "objectMapper", objectMapper);
        ReflectionTestUtils.setField(result, "resultCompressor", compressor);
        ReflectionTestUtils.setField(result, "aiProperties", properties);
        return result;
    }

    private BaseAgentTool slowTool(String name, long delayMs) {
        return new BaseAgentTool() {
            @Override public String name() { return name; }
            @Override public String description() { return name; }
            @Override public Map<String, Object> parameterSchema() { return Map.of(); }
            @Override public AgentToolResult execute(Map<String, Object> input, AgentSessionContext context) {
                try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new AgentToolResult().summary(name).displayText(name);
            }
        };
    }
}
