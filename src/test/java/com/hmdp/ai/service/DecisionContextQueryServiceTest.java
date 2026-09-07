package com.hmdp.ai.service;

import com.hmdp.ai.dto.ConstraintSource;
import com.hmdp.ai.dto.DecisionConstraints;
import com.hmdp.ai.dto.DecisionContextQuery;
import com.hmdp.ai.dto.DecisionRecommendation;
import com.hmdp.ai.dto.DecisionResponse;
import com.hmdp.ai.dto.DecisionTaskState;
import com.hmdp.ai.dto.ConversationWorkingMemory;
import com.hmdp.ai.dto.RecommendationBatch;
import com.hmdp.ai.dto.RecommendationCandidateRef;
import com.hmdp.ai.dto.ReferenceIntent;
import com.hmdp.ai.dto.ResolvedShopReference;
import com.hmdp.ai.entity.AiChatSession;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DecisionContextQueryServiceTest {
    @Test
    void explainsExplicitPreferenceWithoutMutatingState() {
        ConversationStateService stateService = mock(ConversationStateService.class);
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        DecisionTaskState task = new DecisionTaskState();
        task.setTaskId(UUID.randomUUID().toString());
        task.getCriteria().setPreferences(Collections.singletonList("安静"));
        task.getConstraintSources().put("preference:安静", ConstraintSource.USER_EXPLICIT);
        memory.getTasks().add(task);
        memory.setActiveTaskId(task.getTaskId());
        memory.setActiveDecisionSessionId(100L);
        memory.setFocusedShopId(7L);
        memory.setFocusedShopName("聚焦商户");
        RecommendationBatch batch = new RecommendationBatch();
        batch.setDecisionSessionId(100L);
        task.setRecommendationBatches(Collections.singletonList(batch));
        AiChatSession session = new AiChatSession();
        session.setVersion(9);
        when(stateService.workingMemory(org.mockito.ArgumentMatchers.any())).thenReturn(memory);
        when(stateService.activeTask(memory)).thenReturn(task);

        DecisionContextQueryService service = service(stateService, decisionService);
        DecisionContextQuery query = new DecisionContextQuery();
        query.setType(DecisionContextQuery.QueryType.CONSTRAINT_PROVENANCE);
        query.setConstraintKey("preference:安静");
        DecisionContextQueryService.QueryResult result = service.execute(session, query);

        assertTrue(result.answer().contains("明确提出"));
        assertEquals(ConstraintSource.USER_EXPLICIT, result.facts().getSource());
        assertEquals(Collections.singletonList("安静"), memory.activeTask().getCriteria().getPreferences());
        assertEquals(9, session.getVersion());
        assertEquals(100L, memory.getActiveDecisionSessionId());
        assertEquals(7L, memory.getFocusedShopId());
        assertEquals(1, memory.activeTask().getRecommendationBatches().size());
    }

    @Test
    void distinguishesDerivedAndLegacyPreferenceSource() {
        ConversationStateService stateService = mock(ConversationStateService.class);
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        DecisionTaskState task = new DecisionTaskState();
        task.setTaskId(UUID.randomUUID().toString());
        task.getCriteria().setPreferences(Collections.singletonList("安静"));
        memory.getTasks().add(task); memory.setActiveTaskId(task.getTaskId());
        when(stateService.workingMemory(org.mockito.ArgumentMatchers.any())).thenReturn(memory);
        when(stateService.activeTask(memory)).thenReturn(task);
        DecisionContextQueryService service = service(stateService, decisionService);
        DecisionContextQuery query = new DecisionContextQuery();
        query.setType(DecisionContextQuery.QueryType.CONSTRAINT_PROVENANCE);
        query.setConstraintKey("preference:安静");

        assertTrue(service.execute(new AiChatSession(), query).answer().contains("来源"));
        task.getConstraintSources().put("preference:安静", ConstraintSource.DERIVED);
        assertTrue(service.execute(new AiChatSession(), query).answer().contains("推导"));
    }

    @Test
    void explainsHistoricalRecommendationFromDecisionResult() {
        ConversationStateService stateService = mock(ConversationStateService.class);
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        DecisionTaskState task = new DecisionTaskState();
        task.setTaskId(UUID.randomUUID().toString());
        RecommendationBatch batch = new RecommendationBatch(); batch.setDecisionSessionId(100L);
        RecommendationCandidateRef ref = new RecommendationCandidateRef(); ref.setShopId(7L); ref.setShopName("历史火锅店");
        batch.setCandidates(Collections.singletonList(ref)); task.setRecommendationBatches(Collections.singletonList(batch));
        memory.getTasks().add(task); memory.setActiveTaskId(task.getTaskId());
        when(stateService.workingMemory(org.mockito.ArgumentMatchers.any())).thenReturn(memory);
        when(stateService.activeTask(memory)).thenReturn(task);
        DecisionResponse decision = new DecisionResponse();
        DecisionRecommendation recommendation = new DecisionRecommendation(); recommendation.setShopId(7L); recommendation.setShopName("历史火锅店");
        recommendation.setMatchedReasons(Collections.singletonList("菜系符合火锅偏好"));
        recommendation.setEvidence(Collections.singletonList("评论提到环境适合聚餐"));
        decision.setRecommendations(Collections.singletonList(recommendation));
        when(decisionService.getDecision(100L)).thenReturn(decision);

        DecisionContextQuery query = new DecisionContextQuery();
        query.setType(DecisionContextQuery.QueryType.WHY_RECOMMENDED);
        query.setResolvedReference(new ResolvedShopReference(new ReferenceIntent(ReferenceIntent.Scope.EARLIEST, 1, "上一批第一家", 0, 5), batch, 1, 7L, "历史火锅店"));
        DecisionContextQueryService.QueryResult result = service(stateService, decisionService)
                .execute(new AiChatSession(), query);

        assertTrue(result.answer().contains("菜系符合火锅偏好"));
        assertEquals(100L, result.facts().getDecisionSessionId());
    }

    @Test
    void explainsIncompleteExecutedAdministrativeScopeFromPersistedDecision() {
        ConversationStateService stateService = mock(ConversationStateService.class);
        ConsumptionDecisionService decisionService = mock(ConsumptionDecisionService.class);
        ConversationWorkingMemory memory = new ConversationWorkingMemory();
        memory.setActiveDecisionSessionId(3100L);
        when(stateService.workingMemory(org.mockito.ArgumentMatchers.any())).thenReturn(memory);
        DecisionResponse decision = new DecisionResponse();
        DecisionConstraints constraints = new DecisionConstraints();
        constraints.setTargetDistrict("连江县");
        decision.setConstraints(constraints);
        when(decisionService.getDecision(3100L)).thenReturn(decision);

        DecisionContextQuery query = new DecisionContextQuery();
        query.setType(DecisionContextQuery.QueryType.EXECUTED_SEARCH_SCOPE);
        DecisionContextQueryService.QueryResult result = service(stateService, decisionService)
                .execute(new AiChatSession(), query);

        assertTrue(result.answer().contains("连江县"));
        assertTrue(result.answer().contains("没有补全"));
        assertEquals(3100L, result.facts().getDecisionSessionId());
        assertEquals("连江县", result.facts().getExecutedCriteria().getTargetDistrict());
    }

    private DecisionContextQueryService service(ConversationStateService stateService,
                                                ConsumptionDecisionService decisionService) {
        DecisionContextQueryService service = new DecisionContextQueryService();
        ReflectionTestUtils.setField(service, "conversationStateService", stateService);
        ReflectionTestUtils.setField(service, "decisionService", decisionService);
        return service;
    }
}
