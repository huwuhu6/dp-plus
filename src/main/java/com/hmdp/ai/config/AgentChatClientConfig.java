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
        return buildChatClient(properties.getBaseUrl(), properties.getApiKey(), properties.getModel());
    }

    /**
     * Lightweight model for narrative generation and answer polish.
     * Uses qwen-flash by default — suitable for "fact to natural language" tasks
     * that do not require the full reasoning capability of the main model.
     */
    @Bean("lightweightChatClient")
    public ChatClient lightweightChatClient(AiProperties properties) {
        AiProperties.LightweightProperties lw = properties.getLightweight();
        return buildChatClient(lw.getBaseUrl(), lw.getApiKey(), lw.getModel());
    }

    private ChatClient buildChatClient(String baseUrl, String apiKey, String model) {
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(trimTrailingSlash(baseUrl))
                .apiKey(apiKey)
                .completionsPath("/chat/completions")
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(model)
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
