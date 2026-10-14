package com.hmdp.ai.evaluation;
import com.fasterxml.jackson.databind.*;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.v2.runtime.*;
import com.hmdp.ai.v2.semantic.*;
import com.hmdp.ai.v2.plan.*;
import com.hmdp.ai.v2.grounding.V2LocationResolver;
import com.hmdp.ai.dto.*;
import com.hmdp.ai.service.LocationResolutionProvider;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class FirstTurnSourceTest {
    final ObjectMapper json=new ObjectMapper();final OpenAiCompatibleClient client=mock(OpenAiCompatibleClient.class);
    final SemanticInterpreter interpreter=new SemanticInterpreter();
    FirstTurnSourceTest(){ReflectionTestUtils.setField(interpreter,"objectMapper",json);ReflectionTestUtils.setField(interpreter,"aiClient",client);}
    JsonNode response(String args){return json.valueToTree(Map.of("choices",List.of(Map.of("message",Map.of("tool_calls",List.of(Map.of("function",Map.of("arguments",args))))))));}
    String rec(String criteria){return "{\"criteria\":"+criteria+",\"requests\":[{\"requestId\":\"r1\",\"type\":\"RECOMMENDATION\"}]}";}
    DiningCriteriaPatch patch(TurnSemantics sem){return sem.requirementChanges().stream().filter(RequirementChange.CriteriaPatch.class::isInstance).map(RequirementChange.CriteriaPatch.class::cast).map(RequirementChange.CriteriaPatch::patch).findFirst().orElseThrow();}
    @Test void inventedFirstDistanceMustNotBecomeHardConstraint(){
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec("{\"poi\":\"附近\",\"distanceKm\":3}")),response(rec("{\"poi\":\"附近\"}")));
        assertNull(patch(interpreter.interpret("附近找餐厅")).distance());
    }
    @Test void missingBowlOfNoodlesCannotSilentlyPass(){
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec("{\"poi\":\"公司附近\"}")),response("{\"cuisine\":null,\"cuisineAnyOf\":[],\"excludedCuisines\":[]}"));
        assertThrows(SemanticInterpretationException.class,()->interpreter.interpret("公司附近吃碗面"));
    }
    @Test void missingNoodlesCanBeExtractedWithoutInjectingExpectedAnswer(){
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec("{\"poi\":\"公司附近\"}")),response("{\"cuisine\":\"面\",\"cuisineAnyOf\":[],\"excludedCuisines\":[]}"));
        assertEquals("面",patch(interpreter.interpret("公司附近吃碗面")).cuisine().include());
    }
    @Test void explicitKilometresMetresAndChineseCapsRemainHardAndMissingCapRequiresRepair(){
        for(String user:List.of("3公里以内找餐厅","三公里内找餐厅","3000米以内找餐厅")){
            reset(client);when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec("{}")),response(rec("{\"distanceKm\":3}")));
            var p=patch(interpreter.interpret(user));assertEquals(0,p.distance().hardMaxKm().compareTo(java.math.BigDecimal.valueOf(3)));
            var criteria=CriteriaPatchApplier.apply(DiningCriteria.empty(),new RequirementChange.CriteriaPatch(p,Set.of()));
            var scope=SearchRadiusPolicy.forSearch(criteria,null);assertEquals(SearchRadiusPolicy.Source.USER_EXPLICIT,scope.source());assertEquals(3D,scope.maxKm());
        }
    }
    @Test void unsupportedDistanceRepeatedInRepairFailsWithoutTurnCommit()throws Exception{
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec("{\"distanceKm\":3}")));
        var h=new V2PilotHarness(spy(interpreter),"test-invented-distance");assertThrows(SemanticInterpretationException.class,()->h.turn("附近找餐厅",List.of()));assertNull(h.persisted);assertTrue(h.calls.isEmpty());
    }
    @Test void nearbyKeepsOnlyUserIntentAndRequiresAnchorButCityOnlyIsNotDefaultRadius(){
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec("{}")));
        var p=patch(interpreter.interpret("附近找餐厅"));assertTrue(p.location().nearby());assertNull(p.distance());
        var c=CriteriaPatchApplier.apply(DiningCriteria.empty(),new RequirementChange.CriteriaPatch(p,Set.of()));
        assertEquals(SearchRadiusPolicy.Source.SYSTEM_DEFAULT,SearchRadiusPolicy.forSearch(c,null).source());
        assertInstanceOf(V2LocationResolver.Resolution.NeedsClarification.class,new V2LocationResolver(null).resolve(c,null,null));
        var city=new DiningCriteria(new DiningCriteria.LocationCriteria("福州市",null,null),null,null,null,null,DiningCriteria.SemanticPreferences.empty());
        var admin=new SearchAnchor(null,null,null,null,null,"福州市",null,SearchAnchor.AnchorSource.NAMED_LOCATION);
        assertEquals(SearchRadiusPolicy.Source.NONE,SearchRadiusPolicy.forSearch(city,admin).source());
    }
    @Test void publicCompanyPoiEvenUniqueIsNotUsersCompanyAndUnknownLocationDoesNotSearch()throws Exception{
        var provider=mock(LocationResolutionProvider.class);when(provider.isAvailable()).thenReturn(true);
        var candidate=new ResolvedLocationCandidate();candidate.setPoiId("TEST_PUBLIC");candidate.setCanonicalName("TEST_ONLY某公共公司");candidate.setLatitude(26D);candidate.setLongitude(119D);candidate.setSource("PUBLIC_MAP");
        when(provider.resolve(any(LocationResolutionRequest.class))).thenReturn(List.of(candidate));
        var c=new DiningCriteria(new DiningCriteria.LocationCriteria(null,null,"公司附近"),new DiningCriteria.CuisineCriteria("面",List.of()),null,null,null,DiningCriteria.SemanticPreferences.empty());
        assertInstanceOf(V2LocationResolver.Resolution.NeedsClarification.class,new V2LocationResolver(provider).resolve(c,null,null));
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec("{\"poi\":\"公司附近\",\"cuisine\":\"面\"}")));
        var h=new V2PilotHarness(spy(interpreter),"test-company-unknown");ReflectionTestUtils.setField(ReflectionTestUtils.getField(h.chat,"v2ChatOrchestrator"),"locationResolver",new V2LocationResolver(provider));
        var turn=h.turn("公司附近吃碗面",List.of());assertEquals("CLARIFYING",turn.fullResponse().path("decision").path("status").asText());assertTrue(h.calls.isEmpty());
        assertEquals("面",turn.workingMemory().path("tasks").get(0).path("v2Criteria").path("cuisine").path("include").asText());
    }
    @Test void c04OriginalHasNeitherNearbyIntentNorUserNumericRadius(){
        String original=V2ControlledPilotTest.messages().get("C04").getFirst();
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec("{\"cuisine\":\"顺德菜\",\"arrivalTime\":\"周末\",\"distanceKm\":3}")),response(rec("{\"cuisine\":\"顺德菜\",\"arrivalTime\":\"周末\"}")));
        var p=patch(interpreter.interpret(original));assertNull(p.distance());assertNull(p.location());assertEquals("顺德菜",p.cuisine().include());
        var c=CriteriaPatchApplier.apply(DiningCriteria.empty(),new RequirementChange.CriteriaPatch(p,Set.of()));assertEquals(SearchRadiusPolicy.Source.NONE,SearchRadiusPolicy.forSearch(c,null).source());
    }
    @Test void clearCityPoiCuisineEntersSearchSpecAndEmptyRetrievalIsNotSemanticFailure()throws Exception{
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec("{\"city\":\"福州市\",\"poi\":\"三坊七巷\",\"cuisine\":\"闽菜\"}")));
        var h=new V2PilotHarness(spy(interpreter),"test-valid-empty");var turn=h.turn("福州市三坊七巷附近找闽菜餐厅",List.of());
        var spec=turn.executionCalls().getFirst().action().path("spec");assertEquals("闽菜",spec.path("criteria").path("cuisine").path("include").asText());
        assertEquals("福州市",spec.path("criteria").path("location").path("city").asText());assertEquals("三坊七巷",spec.path("searchAnchor").path("canonicalName").asText());
        assertEquals("SYSTEM_DEFAULT",spec.path("radiusPolicy").path("source").asText());assertEquals("EMPTY",turn.executionCalls().getFirst().observation().path("status").asText());
    }
    @Test void ambiguousFaceWordsDoNotIntroduceCuisine(){
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec("{\"cuisine\":\"面\"}")),response("{\"cuisine\":null,\"cuisineAnyOf\":[],\"excludedCuisines\":[]}"));
        assertNull(patch(interpreter.interpret("找个环境体面点的餐厅")).cuisine());
    }
    @Test void askingMerchantDistanceDoesNotRequireAUserHardRadius(){
        when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response("{\"criteria\":{},\"requests\":[{\"requestId\":\"r1\",\"type\":\"FACT\",\"fact\":\"DISTANCE\",\"target\":{\"ordinal\":1}}]}"));
        assertNull(patch(interpreter.interpret("第一家距离3公里以内吗？")).distance());
        verify(client,times(1)).chatCompletion(anyList(),anyList(),anyMap(),anyString());
    }
    @Test void hundredMetresAndHalfKilometreAreExplicitNotNearbyDefaults(){
        assertEquals(0,DistanceSource.explicitMaxKm("百米以内找早餐").compareTo(new java.math.BigDecimal("0.1")));
        assertEquals(0,DistanceSource.explicitMaxKm("半公里内找餐厅").compareTo(new java.math.BigDecimal("0.5")));
        assertNull(DistanceSource.explicitMaxKm("刚走了3公里，找个餐厅"));
    }
    @Test void nearbyAndExplicitRadiusGoThroughActualPreCompilerWithTestDevice()throws Exception{
        for(boolean hard:List.of(false,true)){
            reset(client);when(client.chatCompletion(anyList(),anyList(),anyMap(),anyString())).thenReturn(response(rec(hard?"{\"distanceKm\":3}":"{}")));
            var h=new V2PilotHarness(spy(interpreter),"test-radius-entry-"+hard);h.deviceLocation=V2ControlledPilotTest.knownTestDevice();
            var turn=h.turn(hard?"3公里以内找餐厅":"附近找餐厅",List.of());
            var spec=turn.executionCalls().getFirst().action().path("spec");assertEquals(hard?"USER_EXPLICIT":"SYSTEM_DEFAULT",spec.path("radiusPolicy").path("source").asText());
            assertEquals(3D,spec.path("radiusPolicy").path("initialKm").asDouble());assertEquals(hard?3D:5D,spec.path("radiusPolicy").path("maxKm").asDouble());
            assertEquals(!hard,spec.path("criteria").path("distance").isNull());
        }
        var base=new DiningCriteria(new DiningCriteria.LocationCriteria("福州市",null,"三坊七巷"),null,null,null,null,DiningCriteria.SemanticPreferences.empty());
        var changed=CriteriaPatchApplier.apply(base,new RequirementChange.CriteriaPatch(new DiningCriteriaPatch(new DiningCriteria.LocationCriteria(null,null,null,true),null,null,null,null,null),Set.of()));
        assertEquals("福州市",changed.location().city());assertEquals("三坊七巷",changed.location().poi());assertTrue(changed.location().nearby());
    }
}
