package com.hmdp.ai.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Keeps the conversational model separate from the embedding-model settings. */
@Configuration
public class AgentChatClientConfig {

    @Bean("agentChatClient")
    public ChatClient agentChatClient(AiProperties properties) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(trimTrailingSlash(properties.getBaseUrl()))
                .apiKey(properties.getApiKey())
                .completionsPath("/chat/completions")
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(properties.getModel())
                        .temperature(0.1)
                        .build())
                .build();
        return ChatClient.create(model);
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) return "https://api.deepseek.com";
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
