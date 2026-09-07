package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.dto.ReferenceIntent;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReferenceIntentExtractorTest {
    private final ReferenceIntentExtractor extractor = new ReferenceIntentExtractor();

    @Test
    void extractsMultipleReferencesWithSpans() {
        List<ReferenceIntent> intents = extractor.extract("第一家太贵了，第二家有插座吗？");
        assertEquals(2, intents.size());
        assertEquals(1, intents.get(0).getOrdinal());
        assertEquals(2, intents.get(1).getOrdinal());
        assertEquals("第一家", intents.get(0).getSurface());
        assertEquals("第二家", intents.get(1).getSurface());
        assertEquals(0, intents.get(0).getStart());
        assertEquals(7, intents.get(1).getStart());
    }

    @Test
    void distinguishesEarliestAndFocusedScopes() {
        List<ReferenceIntent> intents = extractor.extract("最开始第一家和刚才那家");
        assertEquals(2, intents.size());
        assertEquals(ReferenceIntent.Scope.EARLIEST, intents.get(0).getScope());
        assertEquals(ReferenceIntent.Scope.FOCUSED, intents.get(1).getScope());
    }

    @Test
    void mergesRuleReferenceWithModelReferenceFromUncoveredSpan() throws Exception {
        ReferenceIntentExtractor target = withModel("{\"intents\":[{\"scope\":\"LATEST\",\"ordinal\":2,\"surface\":\"第二个\",\"start\":7,\"end\":10}]}");

        List<ReferenceIntent> intents = target.extract("第一家太贵了，第二个有插座吗？");

        assertEquals(2, intents.size());
        assertEquals("第一家", intents.get(0).getSurface());
        assertEquals("第二个", intents.get(1).getSurface());
        assertEquals(7, intents.get(1).getStart());
    }

    @Test
    void invokesModelForPureNonStandardReference() throws Exception {
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        ReferenceIntentExtractor target = new ReferenceIntentExtractor();
        ReflectionTestUtils.setField(target, "aiClient", client);
        ReflectionTestUtils.setField(target, "objectMapper", new ObjectMapper());
        when(client.chatCompletion(anyList(), anyList(), isNull(), any(String.class)))
                .thenReturn(new ObjectMapper().readTree("{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"{\\\"intents\\\":[{\\\"scope\\\":\\\"LATEST\\\",\\\"ordinal\\\":1,\\\"surface\\\":\\\"最前面那个\\\",\\\"start\\\":0,\\\"end\\\":5}]}\"}}]}}]}"));

        List<ReferenceIntent> intents = target.extract("最前面那个有券吗？");

        assertEquals(1, intents.size());
        assertEquals("最前面那个", intents.get(0).getSurface());
        verify(client).chatCompletion(anyList(), anyList(), isNull(), any(String.class));
    }

    @Test
    void discardsModelReferenceWhenSurfaceCannotBeLocatedUniquely() throws Exception {
        ReferenceIntentExtractor target = withModel("{\"intents\":[{\"scope\":\"LATEST\",\"ordinal\":2,\"surface\":\"不存在的引用\",\"start\":0,\"end\":2}]}");

        List<ReferenceIntent> intents = target.extract("第一家和第二家有插座吗？");

        assertEquals(2, intents.size());
        assertTrue(intents.stream().noneMatch(item -> "不存在的引用".equals(item.getSurface())));
    }

    @Test
    void keepsStandardMultipleReferencesWhenModelRepeatsThem() throws Exception {
        ReferenceIntentExtractor target = withModel("{\"intents\":[{\"scope\":\"LATEST\",\"ordinal\":1,\"surface\":\"第一家\",\"start\":0,\"end\":3},{\"scope\":\"LATEST\",\"ordinal\":2,\"surface\":\"第二家\",\"start\":7,\"end\":10}]}");

        List<ReferenceIntent> intents = target.extract("第一家太贵了，第二家有插座吗？");

        assertEquals(2, intents.size());
        assertEquals(Arrays.asList(1, 2), intents.stream().map(ReferenceIntent::getOrdinal).toList());
    }

    @Test
    void extractsFocusedReferenceFromThisOne() {
        List<ReferenceIntent> intents = extractor.extract("为什么推荐这个？");
        assertEquals(1, intents.size());
        assertEquals(ReferenceIntent.Scope.FOCUSED, intents.get(0).getScope());
    }

    private ReferenceIntentExtractor withModel(String arguments) throws Exception {
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        when(client.chatCompletion(anyList(), anyList(), isNull(), any(String.class)))
                .thenReturn(new ObjectMapper().readTree("{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":" + quote(arguments) + "}}]}}]}"));
        ReferenceIntentExtractor target = new ReferenceIntentExtractor();
        ReflectionTestUtils.setField(target, "aiClient", client);
        ReflectionTestUtils.setField(target, "objectMapper", new ObjectMapper());
        return target;
    }

    private String quote(String value) throws Exception {
        return new ObjectMapper().writeValueAsString(value);
    }
}
