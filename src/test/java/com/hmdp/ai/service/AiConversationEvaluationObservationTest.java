package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.dto.*;
import com.hmdp.ai.entity.AiWorkingMemory;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AiConversationEvaluationObservationTest {
    @Test void retainsInactiveTasksOrderedBatchesAndFullResponseWithoutChangingMemory() throws Exception {
        ObjectMapper json=new ObjectMapper(); AiConversationEvaluationService service=new AiConversationEvaluationService();
        ReflectionTestUtils.setField(service,"objectMapper",json);
        ConversationWorkingMemory memory=new ConversationWorkingMemory();
        for(String id:List.of("tonight","tomorrow")){
            DecisionTaskState task=new DecisionTaskState();task.setTaskId(id);
            RecommendationBatch batch=new RecommendationBatch();batch.setBatchId(id+"-B1");
            for(long shop:List.of(22L,11L)){RecommendationCandidateRef ref=new RecommendationCandidateRef();ref.setShopId(shop);ref.setShopName("测试"+shop);batch.getCandidates().add(ref);}
            task.getRecommendationBatches().add(batch);memory.getTasks().add(task);
        }
        memory.setActiveTaskId("tomorrow");
        AiWorkingMemory row=new AiWorkingMemory();row.setId(1L);row.setVersion(4);row.setMemoryJson(json.writeValueAsString(memory));
        WorkingMemoryVersionService versions=mock(WorkingMemoryVersionService.class);when(versions.latest("chat")).thenReturn(row);
        ReflectionTestUtils.setField(service,"workingMemoryVersionService",versions);
        ChatMessageResponse response=new ChatMessageResponse();response.setRoute("V2");response.setAnswer("完整内容".repeat(120));
        Object snapshot=ReflectionTestUtils.invokeMethod(service,"captureTurnSnapshot",2,"chat",response,List.of());
        Map<?,?> diagnostic=ReflectionTestUtils.invokeMethod(snapshot,"diagnosticProjection");
        var tree=json.valueToTree(diagnostic);
        assertEquals(2,tree.path("allTasks").size());
        assertEquals("tonight",tree.path("allTasks").get(0).path("taskId").asText());
        assertEquals(22,tree.path("allTasks").get(0).path("recommendationBatches").get(0).path("candidates").get(0).path("shopId").asInt());
        assertEquals(response.getAnswer(),tree.path("fullResponse").path("answer").asText());
        assertEquals("UNAVAILABLE_IN_EXISTING_EVENTS",tree.path("requestExecutionEvidence").asText());
        assertEquals(json.writeValueAsString(memory),row.getMemoryJson());
        verify(versions,never()).append(anyString(),any(),anyInt(),any(),any(),any(),anyMap());
        response.setAnswer("changed later");
        assertEquals(480,tree.path("fullResponse").path("answer").asText().length());
    }
    @Test void reusedDisjointPredicateRejectsEmptyAndRepeatingRecommendations() {
        AiConversationEvaluationService service=new AiConversationEvaluationService();
        assertTrue(service.assertRecommendationDisjoint(List.of(11L,22L),List.of(33L)));
        assertFalse(service.assertRecommendationDisjoint(List.of(11L,22L),List.of(22L,33L)));
        assertFalse(service.assertRecommendationDisjoint(List.of(11L),List.of()));
    }
}
