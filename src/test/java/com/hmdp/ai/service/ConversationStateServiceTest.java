package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.dto.ConversationLocationSlot;
import com.hmdp.ai.dto.CriteriaMergeResult;
import com.hmdp.ai.dto.AgentSessionContext;
import com.hmdp.ai.dto.ChatLocationInput;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.dto.ResolvedLocationCandidate;
import com.hmdp.ai.entity.AiChatSession;
import com.hmdp.ai.entity.AiWorkingMemory;
import com.hmdp.ai.mapper.AiChatSessionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationStateServiceTest {
    @Test
    void clearsCandidatePoolWhenCuisineChanges() throws Exception {
        ConversationStateService service = service();

        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        DecisionConstraints previous = new DecisionConstraints();
        previous.setCuisine("川菜");
        memory.setActiveCriteria(previous);
        DecisionRecommendation shop = new DecisionRecommendation();
        shop.setShopId(10L); shop.setShopName("川味馆");
        memory.setCandidatePool(Arrays.asList(shop));
        memory.setFocusedShopId(10L); memory.setFocusedShopName("川味馆");

        AiChatSession state = new AiChatSession();
        state.setChatId("state-reducer-test"); state.setVersion(1);
        state.setWorkingMemoryJson(new ObjectMapper().writeValueAsString(memory));
        CriteriaMergeResult reduction = new CriteriaMergeResult();
        DecisionConstraints next = new DecisionConstraints();
        next.setCuisine("粤菜");
        reduction.setConstraints(next);
        reduction.getReplaced().add("cuisine:川菜->粤菜");

        service.reduceCriteria(state, reduction);

        ConversationWorkingMemory updated = service.workingMemory(state);
        assertEquals("粤菜", updated.getActiveCriteria().getCuisine());
        assertEquals(0, updated.getCandidatePool().size());
        assertNull(updated.getFocusedShopId());
        assertEquals(Arrays.asList("candidatePool=1", "focusedShop"), reduction.getInvalidated());
    }

    @Test
    void clearsCandidatePoolWhenBudgetOrSceneChanges() throws Exception {
        ConversationStateService service = service();
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        DecisionRecommendation shop = new DecisionRecommendation();
        shop.setShopId(11L); shop.setShopName("旧候选");
        memory.setCandidatePool(Arrays.asList(shop));
        memory.setFocusedShopId(11L); memory.setFocusedShopName("旧候选");
        AiChatSession state = state(memory, "criteria-cascade-test");

        CriteriaMergeResult reduction = new CriteriaMergeResult();
        reduction.setConstraints(new DecisionConstraints());
        reduction.getReplaced().add("budgetPerPerson:100->200");
        reduction.getReplaced().add("occasion:聚餐->约会");

        service.reduceCriteria(state, reduction);

        ConversationWorkingMemory updated = service.workingMemory(state);
        assertEquals(0, updated.getCandidatePool().size());
        assertNull(updated.getFocusedShopId());
        assertEquals(Arrays.asList("candidatePool=1", "focusedShop"), reduction.getInvalidated());
    }

    @Test
    void clearsCandidatePoolWhenLocationChangesMaterially() throws Exception {
        ConversationStateService service = service();
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        ConversationLocationSlot oldLocation = new ConversationLocationSlot();
        oldLocation.setStatus("AVAILABLE"); oldLocation.setLatitude(30.2741D); oldLocation.setLongitude(120.1551D);
        memory.setLocation(oldLocation);
        DecisionRecommendation shop = new DecisionRecommendation();
        shop.setShopId(20L); shop.setShopName("杭州火锅店");
        memory.setCandidatePool(Arrays.asList(shop));
        memory.setFocusedShopId(20L); memory.setFocusedShopName("杭州火锅店");
        AiChatSession state = state(memory, "location-cascade-test");
        ChatLocationInput next = new ChatLocationInput();
        next.setLatitude(26.0789D); next.setLongitude(119.1945D); next.setSource("LOCATION_CONFIRMATION");

        service.acceptLocation(state, next);

        ConversationWorkingMemory updated = service.workingMemory(state);
        assertEquals(0, updated.getCandidatePool().size());
        assertNull(updated.getFocusedShopId());
        assertEquals(26.0789D, updated.getLocation().getLatitude());
    }

    @Test
    void keepsDeviceLocationWhenSearchDestinationIsConfirmed() throws Exception {
        ConversationStateService service = service();
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        ConversationLocationSlot device = memory.getLocation();
        device.setStatus("AVAILABLE"); device.setLatitude(26.0789D); device.setLongitude(119.1945D);
        device.setCity("福州市");
        ResolvedLocationCandidate destination = new ResolvedLocationCandidate();
        destination.setLabel("重庆市"); destination.setLatitude(29.563D); destination.setLongitude(106.551D);
        destination.setCity("重庆市"); destination.setSource("AMAP_MCP");
        memory.setPendingLocationCandidates(Arrays.asList(destination));
        AiChatSession state = state(memory, "target-location-test");

        service.acceptPendingSearchLocation(state, 0);

        ConversationWorkingMemory updated = service.workingMemory(state);
        assertEquals(26.0789D, updated.getLocation().getLatitude());
        assertEquals("福州市", updated.getLocation().getCity());
        assertEquals(29.563D, updated.getSearchLocation().getLatitude());
        assertEquals("重庆市", updated.getSearchLocation().getCity());
        assertEquals(29.563D, service.usableSearchLocation(state).getLatitude());
    }

    @Test
    void namedCityChangeClearsOldTargetCoordinatesAndCandidates() throws Exception {
        ConversationStateService service = service();
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        memory.getSearchLocation().setStatus("AVAILABLE");
        memory.getSearchLocation().setCity("福州");
        memory.getSearchLocation().setLatitude(26.08D);
        memory.getSearchLocation().setLongitude(119.19D);
        DecisionRecommendation stale = new DecisionRecommendation();
        stale.setShopId(99L); stale.setShopName("福州旧候选");
        memory.getCandidatePool().add(stale);
        memory.setFocusedShopId(99L);
        AiChatSession state = state(memory, "named-location-reducer-test");
        CriteriaMergeResult reduction = new CriteriaMergeResult();
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setTargetCity("重庆");
        constraints.setTargetArea("解放碑");
        reduction.setConstraints(constraints);
        reduction.getReplaced().add("targetCity:福州->重庆");
        reduction.getReplaced().add("targetArea:鼓楼区->解放碑");

        service.reduceCriteria(state, reduction);

        ConversationWorkingMemory updated = service.workingMemory(state);
        assertEquals("重庆", updated.getSearchLocation().getCity());
        assertEquals("解放碑", updated.getSearchLocation().getDistrict());
        assertEquals("RESOLVED_BY_NAME", updated.getSearchLocation().getStatus());
        assertNull(updated.getSearchLocation().getLatitude());
        assertEquals(0, updated.getCandidatePool().size());
        assertNull(updated.getFocusedShopId());
    }

    @Test
    void currentDeviceIntentClearsStaleNamedDestinationBeforeLocationGate() throws Exception {
        ConversationStateService service = service();
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        memory.getSearchLocation().setStatus("RESOLVED_BY_NAME");
        memory.getSearchLocation().setCity("北京");
        memory.getSearchLocation().setDistrict("朝阳区");
        AiChatSession state = state(memory, "current-device-location-test");
        CriteriaMergeResult reduction = new CriteriaMergeResult();
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setLocationIntent("CURRENT_DEVICE");
        constraints.setNearby(true);
        reduction.setConstraints(constraints);
        reduction.getCleared().add("targetCity");
        reduction.getCleared().add("targetArea");

        service.reduceCriteria(state, reduction);

        ConversationLocationSlot target = service.workingMemory(state).getSearchLocation();
        assertEquals("MISSING", target.getStatus());
        assertNull(target.getCity());
        assertNull(target.getDistrict());
    }

    @Test
    void appliesFollowUpCandidatePoolIntoWorkingMemory() throws Exception {
        ConversationStateService service = service();
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        AiChatSession state = state(memory, "follow-up-state-test");
        AgentSessionContext context = new AgentSessionContext();
        DecisionRecommendation initial = new DecisionRecommendation();
        initial.setShopId(7L); initial.setShopName("首选店");
        DecisionRecommendation alternative = new DecisionRecommendation();
        alternative.setShopId(8L); alternative.setShopName("备选店");
        context.getShownShops().add(initial); context.getShownShops().add(alternative);
        context.setFocusedShopId(8L); context.setFocusedShopName("备选店");

        service.applyAgentContext(state, 88L, context);

        ConversationWorkingMemory updated = service.workingMemory(state);
        assertEquals(88L, updated.getSourceDecisionSessionId());
        assertEquals(2, updated.getCandidatePool().size());
        assertEquals(8L, updated.getFocusedShopId());
        assertEquals("RECOMMENDING", updated.getDialogPhase());
    }

    @Test
    void rebindsFollowUpContextWhenDecisionSessionChanges() throws Exception {
        ConversationStateService service = service();
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        memory.setSourceDecisionSessionId(10L);
        DecisionRecommendation stale = new DecisionRecommendation();
        stale.setShopId(1L); stale.setShopName("旧会话店铺");
        memory.getCandidatePool().add(stale);
        AiChatSession state = state(memory, "decision-rebind-test");
        DecisionResponse decision = new DecisionResponse();
        decision.setSessionId(20L);
        DecisionRecommendation current = new DecisionRecommendation();
        current.setShopId(2L); current.setShopName("当前会话店铺");
        decision.getRecommendations().add(current);

        AgentSessionContext context = service.contextForDecision(state, decision);

        assertEquals(Long.valueOf(20L), service.workingMemory(state).getSourceDecisionSessionId());
        assertEquals(Long.valueOf(2L), context.getFocusedShopId());
        assertEquals(1, context.getShownShops().size());
        assertEquals("当前会话店铺", context.getShownShops().get(0).getShopName());
    }

    private ConversationStateService service() {
        ConversationStateService service = new ConversationStateService();
        AiChatSessionMapper mapper = mock(AiChatSessionMapper.class);
        when(mapper.update(any(), any())).thenReturn(1);
        ReflectionTestUtils.setField(service, "chatSessionMapper", mapper);
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper());
        WorkingMemoryVersionService versionService = mock(WorkingMemoryVersionService.class);
        when(versionService.append(any(), any(), anyInt(), any(), any(), any(), any())).thenAnswer(invocation -> {
            AiWorkingMemory persisted = new AiWorkingMemory();
            persisted.setId(1L);
            persisted.setVersion(invocation.getArgument(2, Integer.class) + 1);
            persisted.setMemoryJson(objectMapper().writeValueAsString(invocation.getArgument(3)));
            return persisted;
        });
        ReflectionTestUtils.setField(service, "workingMemoryVersionService", versionService);
        return service;
    }

    private AiChatSession state(ConversationWorkingMemory memory, String chatId) throws Exception {
        AiChatSession state = new AiChatSession();
        state.setChatId(chatId); state.setVersion(1);
        state.setWorkingMemoryJson(objectMapper().writeValueAsString(memory));
        return state;
    }

    private ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules();
    }
}
