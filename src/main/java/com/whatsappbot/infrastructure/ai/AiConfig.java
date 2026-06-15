package com.whatsappbot.infrastructure.ai;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.vertexai.VertexAiGeminiChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiConfig {

    @Bean
    public ChatModel geminiChatModel(
            @Value("${gemini.project}") String project,
            @Value("${gemini.location}") String location,
            @Value("${gemini.api.model:gemini-2.5-flash}") String modelName) {
        return VertexAiGeminiChatModel.builder()
                .project(project)
                .location(location)
                .modelName(modelName)
                .temperature(0.0f)
                .build();
    }
}