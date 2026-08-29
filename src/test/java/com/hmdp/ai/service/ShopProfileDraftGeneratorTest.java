package com.hmdp.ai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.ai.client.OpenAiCompatibleClient;
import com.hmdp.ai.config.AiProperties;
import com.hmdp.ai.entity.AiShopProfile;
import com.hmdp.ai.entity.ShopReview;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ShopProfileDraftGeneratorTest {
    @Test
    void acceptsOnlyTheDeclaredStructuredOutput() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        when(client.chatCompletion(any(), any(), any(), any(), any())).thenReturn(response(objectMapper,
                "{\\\"sceneTags\\\":[\\\"约会\\\"],\\\"ambienceTags\\\":[\\\"安静\\\"],\\\"summary\\\":\\\"适合安静约会。\\\"}"));
        ShopProfileDraftGenerator generator = generator(client, objectMapper);

        ShopProfileDraftGenerator.ProfileDraft draft = generator.generate(profile(), List.of(review("环境安静")));

        assertEquals(List.of("约会"), draft.sceneTags());
        assertEquals(List.of("安静"), draft.ambienceTags());
        assertEquals("适合安静约会。", draft.summary());
    }

    @Test
    void rejectsUnexpectedOrFreeFormProfileFields() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        OpenAiCompatibleClient client = mock(OpenAiCompatibleClient.class);
        when(client.chatCompletion(any(), any(), any(), any(), any())).thenReturn(response(objectMapper,
                "{\\\"sceneTags\\\":[],\\\"ambienceTags\\\":[],\\\"summary\\\":\\\"ok\\\",\\\"queueLevel\\\":\\\"HIGH\\\"}"));
        ShopProfileDraftGenerator generator = generator(client, objectMapper);

        assertThrows(IllegalArgumentException.class, () -> generator.generate(profile(), List.of(review("排队"))));
    }

    private ShopProfileDraftGenerator generator(OpenAiCompatibleClient client, ObjectMapper objectMapper) {
        ShopProfileDraftGenerator generator = new ShopProfileDraftGenerator();
        AiProperties properties = new AiProperties();
        properties.setApiKey("test-key");
        ReflectionTestUtils.setField(generator, "aiClient", client);
        ReflectionTestUtils.setField(generator, "aiProperties", properties);
        ReflectionTestUtils.setField(generator, "objectMapper", objectMapper);
        return generator;
    }

    private com.fasterxml.jackson.databind.JsonNode response(ObjectMapper mapper, String arguments) throws Exception {
        return mapper.readTree("{\"choices\":[{\"message\":{\"tool_calls\":[{\"function\":{\"arguments\":\"" + arguments + "\"}}]}}]}");
    }

    private AiShopProfile profile() {
        AiShopProfile profile = new AiShopProfile();
        profile.setCuisine("日料");
        return profile;
    }

    private ShopReview review(String content) {
        ShopReview review = new ShopReview();
        review.setContent(content);
        review.setRating(5);
        return review;
    }
}
