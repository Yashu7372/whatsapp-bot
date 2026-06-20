package com.whatsappbot.infrastructure.ai;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
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

    // In-memory store: wiped on restart/redeploy and not shared across instances.
    // Acceptable for current single-instance deployment; future work is a
    // ChatMemoryStore backed by the MESSAGES table for durability.
    @Bean
    public ChatMemoryProvider chatMemoryProvider(
            @Value("${ai.memory.max-messages:20}") int maxMessages) {
        return memoryId -> MessageWindowChatMemory.withMaxMessages(maxMessages);
    }
}