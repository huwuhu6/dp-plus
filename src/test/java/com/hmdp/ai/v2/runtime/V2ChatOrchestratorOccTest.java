package com.hmdp.ai.v2.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.hmdp.ai.dto.*;
import com.hmdp.ai.entity.AiWorkingMemory;
import com.hmdp.ai.runtime.ConversationEventType;
import com.hmdp.ai.service.ChatMemoryService;
import com.hmdp.ai.service.ConversationEventService;
import com.hmdp.ai.service.VersionConflictException;
import com.hmdp.ai.service.WorkingMemoryVersionService;
import com.hmdp.ai.v2.grounding.V2LocationResolver;
import com.hmdp.ai.v2.semantic.*;
import com.hmdp.mapper.ShopMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class V2ChatOrchestratorOccTest {
    @Test
    void completedAcknowledgementDoesNotMasqueradeAsClarification() {
        ChatMessageResponse response = new V2ResponseRenderer().render("chat", ResponseSpec.completed("任务已结束。"));
        assertEquals("COMPLETED", response.getDecision().getStatus());
        assertEquals("任务已结束。", response.getAnswer());
    }

    @Test
    void postConflictReplansWithoutRepeatingPreMutationAndHidesUncommittedCandidates() throws Exception {
        ObjectMapper json = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        SemanticInterpreter interpreter = mock(SemanticInterpreter.class);
        WorkingMemoryVersionService versions = mock(WorkingMemoryVersionService.class);
        ChatMemoryService chats = mock(ChatMemoryService.class);
        ConversationEventService events = mock(ConversationEventService.class);
        StaticPlanExecutor executor = new StaticPlanExecutor();
        V2ActionHandler actions = mock(V2ActionHandler.class);
        V2ResponseRenderer renderer = new V2ResponseRenderer();
        ShopMapper shopMapper = mock(ShopMapper.class);
        when(chats.resolveChatId("chat")).thenReturn("chat");
        when(chats.load("chat")).thenReturn(List.of());
        when(interpreter.interpret("再便宜一点")).thenReturn(new TurnSemantics(TaskDirective.CONTINUE,
                List.of(new RequirementChange.RelativePreference(DiningCriteria.PreferenceDimension.PRICE, RequirementChange.Direction.LOWER)),
                List.of(), List.of(new UserRequest.RecommendationRequest("recommend")), List.of()));

        AtomicReference<AiWorkingMemory> current = new AtomicReference<>();
        when(versions.latest("chat")).thenAnswer(ignored -> current.get());
        AtomicBoolean postConflictInjected = new AtomicBoolean();
        doAnswer(invocation -> {
            int expected = invocation.getArgument(2);
            ConversationWorkingMemory memory = (ConversationWorkingMemory) invocation.getArgument(3);
            if (expected == 1 && postConflictInjected.compareAndSet(false, true)) {
                // A competing turn changed a causal planning input after PRE but before this POST commit.
                ConversationWorkingMemory concurrent = json.readValue(current.get().getMemoryJson(), ConversationWorkingMemory.class);
                concurrent.activeTask().getV2Locked().add(DiningCriteria.PreferenceDimension.PRICE);
                current.set(row(2, json.writeValueAsString(concurrent)));
                throw new VersionConflictException("chat", expected, 2, "V2_POST");
            }
            AiWorkingMemory committed = row(expected + 1, json.writeValueAsString(memory));
            current.set(committed);
            return committed;
        }).when(versions).append(anyString(), nullable(Long.class), anyInt(), any(), any(ConversationEventType.class), any(), anyMap());

        AtomicInteger executions = new AtomicInteger();
        when(actions.execute(any())).thenAnswer(invocation -> {
            int attempt = executions.incrementAndGet();
            long shopId = attempt == 1 ? 1L : 2L;
            DecisionRecommendation candidate = new DecisionRecommendation();
            candidate.setShopId(shopId); candidate.setShopName("餐厅" + shopId); candidate.setAvgPrice(50L);
            return new ExecutionObservation("recommend", ExecutionObservation.Status.SUCCESS, List.of(shopId), null,
                    List.of(), null, null, null, List.of(candidate));
        });

        V2ChatOrchestrator orchestrator = new V2ChatOrchestrator();
        ReflectionTestUtils.setField(orchestrator, "semanticInterpreter", interpreter);
        ReflectionTestUtils.setField(orchestrator, "versions", versions);
        ReflectionTestUtils.setField(orchestrator, "chatMemoryService", chats);
        ReflectionTestUtils.setField(orchestrator, "conversationEventService", events);
        ReflectionTestUtils.setField(orchestrator, "objectMapper", json);
        ReflectionTestUtils.setField(orchestrator, "executor", executor);
        ReflectionTestUtils.setField(orchestrator, "actions", actions);
        ReflectionTestUtils.setField(orchestrator, "renderer", renderer);
        ReflectionTestUtils.setField(orchestrator, "shopMapper", shopMapper);
        ReflectionTestUtils.setField(orchestrator, "locationResolver", new V2LocationResolver(null));

        ChatMessageRequest request = new ChatMessageRequest(); request.setChatId("chat"); request.setMessage("再便宜一点");
        ChatMessageResponse response = orchestrator.chat(request);

        assertEquals(2, executions.get(), "causal post conflict replans and executes exactly once more; answer="
                + response.getAnswer() + ", version=" + current.get().getVersion() + ", memory=" + current.get().getMemoryJson());
        assertEquals(List.of(2L), response.getDecision().getRecommendations().stream().map(DecisionRecommendation::getShopId).toList(),
                "the uncommitted stale batch must never be rendered");
        ConversationWorkingMemory persisted = json.readValue(current.get().getMemoryJson(), ConversationWorkingMemory.class);
        DecisionTaskState task = persisted.activeTask();
        assertEquals(1, task.getV2RelativePreferences().size(), "PRE relative mutation must not be replayed");
        assertTrue(task.getV2Locked().contains(DiningCriteria.PreferenceDimension.PRICE));
        assertEquals(1, task.getRecommendationBatches().size(), "only the committed execution creates a batch");
        assertEquals(2L, task.getRecommendationBatches().getFirst().getCandidates().getFirst().getShopId());
        ArgumentCaptor<Map<String, Object>> phases = ArgumentCaptor.forClass(Map.class);
        verify(versions, times(3)).append(anyString(), nullable(Long.class), anyInt(), any(), any(ConversationEventType.class), phases.capture(), anyMap());
        assertEquals("V2_PRE", phases.getAllValues().getFirst().get("phase"));
        assertEquals("V2_POST_RETRY", phases.getAllValues().getLast().get("phase"));
        verify(chats).appendTurn(eq("chat"), eq("再便宜一点"), anyString(), eq("V2"), isNull());
        verify(events).begin("chat", 1);
        verify(events).clearTrace();
    }

    private AiWorkingMemory row(int version, String json) {
        AiWorkingMemory row = new AiWorkingMemory(); row.setVersion(version); row.setMemoryJson(json); row.setChatId("chat"); return row;
    }
}
