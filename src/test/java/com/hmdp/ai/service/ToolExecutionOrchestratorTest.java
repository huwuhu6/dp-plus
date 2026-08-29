package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.ai.dto.ChatStreamEventData;
import com.hmdp.ai.tool.AgentToolRegistry;
import com.hmdp.ai.tool.AgentToolResult;
import com.hmdp.ai.tool.BaseAgentTool;
import com.hmdp.ai.tool.ToolExecutionRequest;
import com.hmdp.ai.tool.ToolExecutionResult;
import com.hmdp.ai.tool.ToolResultCompressor;
import com.hmdp.ai.runtime.RuntimeTrace;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
            @Override public AgentToolResult execute(Map<String, Object> input) {
                try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return new AgentToolResult().summary(name).displayText(name);
            }
        };
    }

    @Test
    void materializesFocusedShopIntoToolInputWithoutMutatingContext() {
        AgentToolRegistry registry = mock(AgentToolRegistry.class);
        BaseAgentTool detail = mock(BaseAgentTool.class);
        when(registry.find("get_shop_detail")).thenReturn(detail);
        when(detail.executionMode()).thenReturn(com.hmdp.ai.tool.ToolExecutionMode.PARALLEL_SAFE);
        when(detail.execute(anyMap())).thenReturn(new AgentToolResult().summary("detail").displayText("detail"));
        AgentSessionContext context = new AgentSessionContext();
        context.setFocusedShopId(18L); context.setFocusedShopName("当前店");

        ToolExecutionResult result = orchestrator(registry).executeOne(
                new ToolExecutionRequest(0, "get_shop_detail", "{}", null), context);

        assertEquals(true, result.isSuccess());
        assertEquals("{\"shopId\":18}", result.getEffectiveArguments());
        assertEquals(18L, context.getFocusedShopId());
        assertEquals("当前店", context.getFocusedShopName());
    }

    @Test
    void preservesPlannerShopIdInsteadOfOverwritingItWithFocusedReference() {
        AgentToolRegistry registry = mock(AgentToolRegistry.class);
        AtomicReference<Map<String, Object>> inputHolder = new AtomicReference<Map<String, Object>>();
        BaseAgentTool detail = new BaseAgentTool() {
            @Override public String name() { return "get_shop_detail"; }
            @Override public String description() { return "detail"; }
            @Override public Map<String, Object> parameterSchema() { return Map.of(); }
            @Override public AgentToolResult execute(Map<String, Object> input) {
                inputHolder.set(input);
                return new AgentToolResult().summary("detail").displayText("detail");
            }
        };
        when(registry.find("get_shop_detail")).thenReturn(detail);

        ToolExecutionResult result = orchestrator(registry).executeOne(
                new ToolExecutionRequest(0, "get_shop_detail", "{\"shopId\":74}", 89L), new AgentSessionContext());

        assertTrue(result.isSuccess());
        assertEquals(74, ((Number) inputHolder.get().get("shopId")).intValue());
        assertEquals("{\"shopId\":74}", result.getEffectiveArguments());
    }

    @Test
    void publishesToolStartAndEndEventsWithEffectiveArgumentsAndResultSummary() {
        AgentToolRegistry registry = mock(AgentToolRegistry.class);
        BaseAgentTool detail = new BaseAgentTool() {
            @Override public String name() { return "get_shop_detail"; }
            @Override public String description() { return "detail"; }
            @Override public Map<String, Object> parameterSchema() { return Map.of(); }
            @Override public AgentToolResult execute(Map<String, Object> input) {
                AgentToolResult result = new AgentToolResult().summary("shop detail").displayText("shop detail");
                result.setFacts(Map.of("shopId", input.get("shopId")));
                return result;
            }
        };
        when(registry.find("get_shop_detail")).thenReturn(detail);
        List<ChatStreamEventData> events = new ArrayList<ChatStreamEventData>();

        orchestrator(registry).executeOne(new ToolExecutionRequest(0, "get_shop_detail", "{\"shopId\":18}", null),
                new AgentSessionContext(), events::add);

        assertEquals(2, events.size());
        assertEquals("tool_event", events.get(0).getEventName());
        assertEquals("start", events.get(0).getStage());
        assertTrue(events.get(0).getToolCallId() != null && !events.get(0).getToolCallId().isBlank());
        assertEquals("{\"shopId\":18}", events.get(0).getArguments());
        assertEquals("end", events.get(1).getStage());
        assertEquals(events.get(0).getToolCallId(), events.get(1).getToolCallId());
        assertTrue(events.get(1).getDurationMs() >= 0L);
        assertEquals("shop detail", ((Map<?, ?>) events.get(1).getOutput()).get("summary"));
    }

    @Test
    void persistsDurableCallBeforeExecutingTool() {
        AgentToolRegistry registry = mock(AgentToolRegistry.class);
        ConversationEventService events = mock(ConversationEventService.class);
        BaseAgentTool detail = mock(BaseAgentTool.class);
        when(registry.find("get_shop_detail")).thenReturn(detail);
        when(detail.execute(anyMap())).thenReturn(new AgentToolResult().summary("detail").displayText("detail"));
        when(events.persistDurableEvent(org.mockito.ArgumentMatchers.any(RuntimeTrace.class),
                org.mockito.ArgumentMatchers.eq(com.hmdp.ai.runtime.ConversationEventType.TOOL_CALL),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(91L);
        ToolExecutionOrchestrator orchestrator = orchestrator(registry);
        ReflectionTestUtils.setField(orchestrator, "conversationEventService", events);

        ToolExecutionResult result = orchestrator.execute(List.of(new ToolExecutionRequest(0, "get_shop_detail", "{}", null)),
                new AgentSessionContext(), null, 8L, new RuntimeTrace("chat-tool", "trace-tool", 1)).get(0);

        assertEquals(91L, result.getToolCallEventId());
        org.mockito.InOrder order = inOrder(events, detail);
        order.verify(events).persistDurableEvent(org.mockito.ArgumentMatchers.any(RuntimeTrace.class),
                org.mockito.ArgumentMatchers.eq(com.hmdp.ai.runtime.ConversationEventType.TOOL_CALL),
                org.mockito.ArgumentMatchers.eq(com.hmdp.ai.runtime.ConversationEventStatus.RUNNING),
                org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.isNull(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.isNull());
        order.verify(detail).execute(anyMap());
    }

    @Test
    void doesNotExecuteToolWhenDurableCallCannotBeWritten() {
        AgentToolRegistry registry = mock(AgentToolRegistry.class);
        ConversationEventService events = mock(ConversationEventService.class);
        BaseAgentTool detail = mock(BaseAgentTool.class);
        when(registry.find("get_shop_detail")).thenReturn(detail);
        when(events.persistDurableEvent(org.mockito.ArgumentMatchers.any(RuntimeTrace.class),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenThrow(new IllegalStateException("db unavailable"));
        ToolExecutionOrchestrator orchestrator = orchestrator(registry);
        ReflectionTestUtils.setField(orchestrator, "conversationEventService", events);

        ToolExecutionResult result = orchestrator.execute(List.of(new ToolExecutionRequest(0, "get_shop_detail", "{}", null)),
                new AgentSessionContext(), null, 8L, new RuntimeTrace("chat-tool", "trace-tool", 1)).get(0);

        assertTrue(!result.isSuccess());
        verify(detail, never()).execute(anyMap());
    }
}
