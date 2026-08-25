package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.dto.ConversationLocationSlot;
import com.hmdp.ai.dto.CriteriaMergeResult;
import com.hmdp.ai.dto.ChatLocationInput;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.ResolvedLocationCandidate;
import com.hmdp.ai.entity.AiChatSession;
import com.hmdp.ai.mapper.AiChatSessionMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ConversationStateServiceTest {
    @Test
    void clearsCandidatePoolWhenCuisineChanges() throws Exception {
        ConversationStateService service = new ConversationStateService();
        AiChatSessionMapper mapper = mock(AiChatSessionMapper.class);
        when(mapper.update(any(), any())).thenReturn(1);
        ReflectionTestUtils.setField(service, "chatSessionMapper", mapper);
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper());

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

    private ConversationStateService service() {
        ConversationStateService service = new ConversationStateService();
        AiChatSessionMapper mapper = mock(AiChatSessionMapper.class);
        when(mapper.update(any(), any())).thenReturn(1);
        ReflectionTestUtils.setField(service, "chatSessionMapper", mapper);
        ReflectionTestUtils.setField(service, "objectMapper", objectMapper());
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
