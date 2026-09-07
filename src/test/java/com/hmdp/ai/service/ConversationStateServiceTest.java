package com.hmdp.ai.service;

import com.hmdp.ai.dto.*;
import com.hmdp.ai.entity.AiChatSession;
import com.hmdp.ai.entity.AiWorkingMemory;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** V2 model tests construct Task/RecommendationBatch, never flat entity context. */
class ConversationStateServiceTest {
    @Test void taskSwitchKeepsCanonicalCriteria() {
        ConversationStateService service = new ConversationStateService(); ConversationWorkingMemory memory = new ConversationWorkingMemory();
        DecisionTaskState a = service.createTask(memory, "福州火锅"); a.getCriteria().setBudgetPerPerson(100);
        DecisionTaskState b = service.createTask(memory, "杭州日料"); b.getCriteria().setBudgetPerPerson(300);
        assertTrue(service.activateHistoricalTask(memory, "回到最开始那个")); service.activeCriteria(memory).setBudgetPerPerson(80);
        assertEquals(80, a.getCriteria().getBudgetPerPerson()); assertEquals(300, b.getCriteria().getBudgetPerPerson());
    }
    @Test void batchProjectionRetainsHistory() {
        ConversationStateService service = new ConversationStateService(); ConversationWorkingMemory memory = new ConversationWorkingMemory();
        TestTaskFixture.append(memory, 10L, Arrays.asList(shop(1), shop(2), shop(3))); TestTaskFixture.append(memory, 11L, Arrays.asList(shop(4), shop(5), shop(6)));
        assertEquals(Arrays.asList(4L,5L,6L), ids(service.latestCandidatePool(memory)));
        assertEquals(Arrays.asList(1L,2L,3L,4L,5L,6L), service.shownShopIds(memory)); assertEquals(2, service.activeTask(memory).getRecommendationBatches().size()); assertEquals(11L, service.latestSourceDecisionSessionId(memory));
    }

    @Test void excludedCuisineInvalidatesCurrentProjectionButKeepsBatchHistory() throws Exception {
        ConversationStateService service = new ConversationStateService();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectMapper", mapper);
        WorkingMemoryVersionService versionService = org.mockito.Mockito.mock(WorkingMemoryVersionService.class);
        org.mockito.Mockito.when(versionService.append(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    AiWorkingMemory committed = new AiWorkingMemory();
                    committed.setVersion(1);
                    committed.setMemoryJson(mapper.writeValueAsString(invocation.getArgument(3)));
                    return committed;
                });
        org.springframework.test.util.ReflectionTestUtils.setField(service, "workingMemoryVersionService", versionService);

        AiChatSession state = new AiChatSession();
        state.setChatId("excluded-cuisine-test");
        state.setVersion(0);
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        TestTaskFixture.append(memory, 10L, Arrays.asList(shop(1), shop(2), shop(3)));
        memory.setFocusedShopId(1L);
        memory.setFocusedShopName("shop-1");
        state.setWorkingMemoryJson(mapper.writeValueAsString(memory));

        DecisionConstraints constraints = service.activeCriteria(memory);
        constraints.setExcludedCuisines(Collections.singletonList("东北菜"));
        CriteriaMergeResult reduction = new CriteriaMergeResult();
        reduction.setConstraints(constraints);
        reduction.getAppended().add("excludedCuisines:东北菜");

        service.reduceCriteria(state, memory, reduction);

        ConversationWorkingMemory reduced = service.workingMemory(state);
        assertEquals(Collections.emptyList(), service.latestCandidatePool(reduced));
        assertNull(reduced.getFocusedShopId());
        assertEquals(2, service.activeTask(reduced).getRecommendationBatches().size());
        assertEquals(Arrays.asList(1L, 2L, 3L), service.shownShopIds(reduced));
    }
    @Test void nearbyNormalizationUsesDefaultOnlyWhenRadiusIsUnspecified() {
        DecisionConstraints unspecified = new DecisionConstraints();
        unspecified.setNearby(true); unspecified.setRadiusKm(-1D);
        assertTrue(ConversationStateService.normalizeNearbyRadius(unspecified));
        assertEquals(3D, unspecified.getRadiusKm());

        DecisionConstraints explicit = new DecisionConstraints();
        explicit.setNearby(true); explicit.setRadiusKm(5D);
        assertFalse(ConversationStateService.normalizeNearbyRadius(explicit));
        assertEquals(5D, explicit.getRadiusKm());
    }

    @Test void appliesPreferenceSourceUpdatesToTaskProvenance() {
        ConversationStateService service = new ConversationStateService();
        DecisionTaskState task = new DecisionTaskState();
        CriteriaMergeResult reduction = new CriteriaMergeResult();
        reduction.getSourceUpdates().put("preference:安静", ConstraintSource.USER_EXPLICIT);
        service.markConstraintSourcesForTest(task, reduction);
        assertEquals(ConstraintSource.USER_EXPLICIT, task.getConstraintSources().get("preference:安静"));
        reduction.getSourceUpdates().put("preference:安静", null);
        service.markConstraintSourcesForTest(task, reduction);
        assertFalse(task.getConstraintSources().containsKey("preference:安静"));
    }

    @Test void currentDeviceProjectionClearsNamedTaskScope() throws Exception {
        ConversationStateService service = new ConversationStateService();
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        org.springframework.test.util.ReflectionTestUtils.setField(service, "objectMapper", mapper);
        WorkingMemoryVersionService versionService = org.mockito.Mockito.mock(WorkingMemoryVersionService.class);
        org.mockito.Mockito.when(versionService.append(org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyInt(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    AiWorkingMemory committed = new AiWorkingMemory();
                    committed.setVersion(1);
                    committed.setMemoryJson(mapper.writeValueAsString(invocation.getArgument(3)));
                    return committed;
                });
        org.springframework.test.util.ReflectionTestUtils.setField(service, "workingMemoryVersionService", versionService);
        AiChatSession state = new AiChatSession();
        state.setChatId("test-chat");
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        DecisionTaskState task = service.createTask(memory, "北京日料");
        task.getCriteria().setTargetProvince("北京市");
        task.getCriteria().setTargetCity("北京");
        task.getCriteria().setLocationIntent("EXPLICIT_TARGET");
        task.getSearchLocation().setStatus("RESOLVED_BY_NAME");
        task.getSearchLocation().setCity("北京");
        state.setWorkingMemoryJson(mapper.writeValueAsString(memory));

        DecisionConstraints execution = new DecisionConstraints();
        execution.setLocationIntent("CURRENT_DEVICE");
        execution.setNearby(true);
        execution.setRadiusKm(5D);
        service.applyCurrentDeviceSearchScope(state, execution);

        ConversationWorkingMemory projected = service.workingMemory(state);
        assertEquals("CURRENT_DEVICE", projected.getTasks().get(0).getCriteria().getLocationIntent());
        assertEquals("", projected.getTasks().get(0).getCriteria().getTargetProvince());
        assertEquals("", projected.getTasks().get(0).getCriteria().getTargetCity());
        assertEquals("", projected.getTasks().get(0).getCriteria().getTargetArea());
        assertEquals(5D, projected.getTasks().get(0).getCriteria().getRadiusKm());
        assertEquals("MISSING", projected.getTasks().get(0).getSearchLocation().getStatus());
    }
    private DecisionRecommendation shop(long id) { DecisionRecommendation value = new DecisionRecommendation(); value.setShopId(id); value.setShopName("shop-" + id); return value; }
    private List<Long> ids(List<DecisionRecommendation> values) { List<Long> result = new ArrayList<>(); for (DecisionRecommendation v : values) result.add(v.getShopId()); return result; }
}
