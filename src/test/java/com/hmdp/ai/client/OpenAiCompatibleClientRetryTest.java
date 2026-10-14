package com.hmdp.ai.client;

import com.fasterxml.jackson.databind.*;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.dto.DecisionMetrics;
import com.hmdp.ai.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.*;
import java.nio.charset.StandardCharsets;
import java.net.SocketTimeoutException;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OpenAiCompatibleClientRetryTest {
    static class Client extends OpenAiCompatibleClient {
        final RestTemplate http=mock(RestTemplate.class);
        final List<Long> sleeps=new ArrayList<>();long now;boolean interrupt;
        @Override protected RestTemplate restTemplate(Integer timeout){return http;}
        @Override protected long nanoTime(){return now;}
        @Override protected long jitter(long cap){return cap;}
        @Override protected void pause(long ms)throws InterruptedException{if(interrupt)throw new InterruptedException();sleeps.add(ms);now+=ms*1_000_000;}
    }
    final Client client=new Client();final AiProperties config=new AiProperties();
    final AiModelCallTracker tracker=new AiModelCallTracker();final AiModelCallObservationService observations=new AiModelCallObservationService();
    final DecisionMetrics metrics=new DecisionMetrics();final ObjectMapper json=new ObjectMapper();
    OpenAiCompatibleClientRetryTest(){config.setApiKey("TEST_ONLY");ReflectionTestUtils.setField(client,"aiProperties",config);
        ReflectionTestUtils.setField(client,"objectMapper",json);ReflectionTestUtils.setField(client,"modelCallTracker",tracker);
        ReflectionTestUtils.setField(client,"modelCallObservationService",observations);tracker.start(metrics);observations.begin();}
    ResponseEntity<JsonNode> ok(){return ResponseEntity.ok(json.valueToTree(Map.of("choices",List.of(),"usage",Map.of("prompt_tokens",10,"completion_tokens",5))));}
    HttpStatusCodeException error(int status,String body,String after){HttpHeaders h=new HttpHeaders();if(after!=null)h.set("Retry-After",after);
        return status<500?HttpClientErrorException.create(HttpStatus.valueOf(status),"test",h,body.getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8):
                HttpServerErrorException.create(HttpStatus.valueOf(status),"test",h,body.getBytes(StandardCharsets.UTF_8),StandardCharsets.UTF_8);}
    JsonNode call(){return client.chatCompletion(List.of(Map.of("role","user","content","original")),List.of(),null,"TEST");}
    @Test void transientFailuresRecoverWithExponentialBackoffAndIdenticalRequest(){
        when(client.http.postForEntity(anyString(),any(),eq(JsonNode.class))).thenThrow(error(503,"{}",null),error(429,"{}",null)).thenReturn(ok());
        assertNotNull(call());assertEquals(List.of(500L,1000L),client.sleeps);
        var requests=org.mockito.ArgumentCaptor.forClass(HttpEntity.class);verify(client.http,times(3)).postForEntity(anyString(),requests.capture(),eq(JsonNode.class));
        assertSame(requests.getAllValues().getFirst(),requests.getAllValues().getLast());
        assertEquals(3,metrics.getModelCallCount());assertEquals(2,metrics.getModelFailureCount());assertEquals(1,metrics.getModelSuccessCount());
        assertEquals(3,observations.snapshot().size());assertEquals(3,client.lastTransportAttempts().size());
        assertFalse((boolean)client.lastTransportAttempts().getFirst().get("success"));
    }
    @Test void knownProviderGrammarTimeout400IsRetryable(){
        String body="{\"error\":{\"code\":\"invalid_parameter_error\",\"message\":\"grammar validation or compilation failed: sandbox grammar compile timed out\"}}";
        when(client.http.postForEntity(anyString(),any(),eq(JsonNode.class))).thenThrow(error(400,body,null)).thenReturn(ok());
        call();assertEquals("GRAMMAR_COMPILE_TIMEOUT",client.lastTransportAttempts().getFirst().get("retryReason"));assertEquals(2,metrics.getModelCallCount());
    }
    @Test void permanentErrorsNeverRetry(){for(int code:List.of(400,401,403,404,422)){
        reset(client.http);when(client.http.postForEntity(anyString(),any(),eq(JsonNode.class))).thenThrow(error(code,"{\"error\":{\"message\":\"invalid schema\"}}",null));
        assertThrows(HttpStatusCodeException.class,this::call);verify(client.http).postForEntity(anyString(),any(),eq(JsonNode.class));assertTrue(client.sleeps.isEmpty());}}
    @Test void exhaustedRetriesKeepAllFailures(){when(client.http.postForEntity(anyString(),any(),eq(JsonNode.class))).thenThrow(error(502,"{}",null));
        assertThrows(HttpStatusCodeException.class,this::call);assertEquals(3,metrics.getModelFailureCount());assertEquals(3,client.lastTransportAttempts().size());}
    @Test void socketTimeoutRetriesButCertificateFailureDoesNot(){
        when(client.http.postForEntity(anyString(),any(),eq(JsonNode.class))).thenThrow(new ResourceAccessException("test",new SocketTimeoutException())).thenReturn(ok());
        call();assertEquals("NETWORK_IO",client.lastTransportAttempts().getFirst().get("retryReason"));
        reset(client.http);when(client.http.postForEntity(anyString(),any(),eq(JsonNode.class))).thenThrow(new ResourceAccessException("test",new javax.net.ssl.SSLHandshakeException("test")));
        assertThrows(ResourceAccessException.class,this::call);assertEquals(1,client.lastTransportAttempts().size());
    }
    @Test void retryAfterIsRespectedAndBudgetStopsLongWait(){
        when(client.http.postForEntity(anyString(),any(),eq(JsonNode.class))).thenThrow(error(429,"{}","2")).thenReturn(ok());call();assertEquals(List.of(2000L),client.sleeps);
        reset(client.http);config.getModelRetry().setMaxElapsedMs(1000L);
        when(client.http.postForEntity(anyString(),any(),eq(JsonNode.class))).thenThrow(error(429,"{}","2"));
        assertThrows(HttpStatusCodeException.class,this::call);assertEquals("TIME_BUDGET",client.lastTransportAttempts().getFirst().get("retryStopped"));
    }
    @Test void interruptStopsRetryAndPreservesFlag(){client.interrupt=true;when(client.http.postForEntity(anyString(),any(),eq(JsonNode.class))).thenThrow(error(503,"{}",null));
        try{assertThrows(HttpStatusCodeException.class,this::call);assertTrue(Thread.currentThread().isInterrupted());assertEquals(1,client.lastTransportAttempts().size());}
        finally{Thread.interrupted();}}
    @Test void successfulResponseIsNeverRegenerated(){when(client.http.postForEntity(anyString(),any(),eq(JsonNode.class))).thenReturn(ok());call();verify(client.http).postForEntity(anyString(),any(),eq(JsonNode.class));assertTrue(client.sleeps.isEmpty());}
    @Test void fullJitterStaysWithinCap(){var actual=new OpenAiCompatibleClient();for(int n=0;n<50;n++){long delay=actual.jitter(500);assertTrue(delay>=0 && delay<=500);}assertEquals(0,actual.jitter(0));}
    @Test void timeConsumedByFailedRequestExhaustsBudgetWithoutAnotherAttempt(){
        config.getModelRetry().setMaxElapsedMs(1000L);
        when(client.http.postForEntity(anyString(),any(),eq(JsonNode.class))).thenAnswer(i->{client.now+=1_000_000_000L;throw error(503,"{}",null);});
        assertThrows(HttpStatusCodeException.class,this::call);verify(client.http).postForEntity(anyString(),any(),eq(JsonNode.class));assertTrue(client.sleeps.isEmpty());
    }
    @Test void retryAfterHttpDateIsSupported(){
        String date=java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC).plusSeconds(30).format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME);
        when(client.http.postForEntity(anyString(),any(),eq(JsonNode.class))).thenThrow(error(503,"{}",date)).thenReturn(ok());call();
        assertTrue(client.sleeps.getFirst()>20000 && client.sleeps.getFirst()<=30000);
    }
}
