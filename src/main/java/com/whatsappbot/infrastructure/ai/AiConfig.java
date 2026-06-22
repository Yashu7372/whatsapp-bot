package com.whatsappbot.infrastructure.ai;

import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    /**
     * The single {@link ChatModel} bean the rest of the application depends
     * on. Which provider backs it is decided entirely by {@link AiProperties}
     * (ai.provider / AI_PROVIDER env var) via {@link ChatModelFactory} —
     * nothing else in the codebase references a provider-specific class.
     */
    @Bean
    public ChatModel chatModel(AiProperties aiProperties) {
        return ChatModelFactory.build(aiProperties);
    }

    // In-memory store: wiped on restart/redeploy and not shared across instances.
    // Acceptable for current single-instance deployment; future work is a
    // ChatMemoryStore backed by the MESSAGES table for durability.
    @Bean
    public ChatMemoryProvider chatMemoryProvider(AiProperties aiProperties) {
        int maxMessages = aiProperties.getMemoryMaxMessages();
        return memoryId -> MessageWindowChatMemory.withMaxMessages(maxMessages);
    }
}