package com.whatsappbot.infrastructure.ai;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;

/**
 * Builds the active {@link ChatModel} from {@link AiProperties}.
 * <p>
 * This is the single point where "which AI provider" turns into "which
 * LangChain4j client object." Adding a fifth provider means adding one
 * branch here and one enum value in {@link AiProvider} — every other class
 * in the application (TenantAiService, WhatsAppAgent, all @Tool classes)
 * depends only on the {@link ChatModel} interface and never knows or cares
 * which provider is behind it.
 * <p>
 * Switching providers in production or locally is: change {@code AI_PROVIDER}
 * (and that provider's own env vars), restart the container. No recompile,
 * no redeploy of new code — only a config change and a process restart.
 */
@Slf4j
public class ChatModelFactory {

    private ChatModelFactory() {
    }

    public static ChatModel build(AiProperties properties) {
        AiProvider provider = properties.getProvider();
        log.info("Initializing AI chat model. provider={}", provider);

        return switch (provider) {
            case GEMINI -> buildGemini(properties);
            case OLLAMA -> buildOllama(properties);
            case OPENAI -> buildOpenAi(properties);
            case ANTHROPIC -> buildAnthropic(properties);
        };
    }

    private static ChatModel buildGemini(AiProperties properties) {
        AiProperties.Gemini config = properties.getGemini();
        requireNonBlank(config.getApiKey(), "ai.gemini.api-key", "AI_GEMINI_API_KEY");

        return GoogleAiGeminiChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(properties.getTemperature())
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .build();
    }

    private static ChatModel buildOllama(AiProperties properties) {
        AiProperties.Ollama config = properties.getOllama();
        requireNonBlank(config.getBaseUrl(), "ai.ollama.base-url", "AI_OLLAMA_BASE_URL");

        return OllamaChatModel.builder()
                .baseUrl(config.getBaseUrl())
                .modelName(config.getModelName())
                .temperature(properties.getTemperature())
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .build();
    }

    private static ChatModel buildOpenAi(AiProperties properties) {
        AiProperties.OpenAi config = properties.getOpenai();
        requireNonBlank(config.getApiKey(), "ai.openai.api-key", "AI_OPENAI_API_KEY");

        return OpenAiChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(properties.getTemperature())
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .build();
    }

    private static ChatModel buildAnthropic(AiProperties properties) {
        AiProperties.Anthropic config = properties.getAnthropic();
        requireNonBlank(config.getApiKey(), "ai.anthropic.api-key", "AI_ANTHROPIC_API_KEY");

        return AnthropicChatModel.builder()
                .apiKey(config.getApiKey())
                .modelName(config.getModelName())
                .temperature(properties.getTemperature())
                .timeout(Duration.ofSeconds(properties.getTimeoutSeconds()))
                .build();
    }

    private static void requireNonBlank(String value, String propertyPath, String envVar) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "Missing required AI config '%s' (env var %s). Set it in your environment/.env file for the selected ai.provider."
                            .formatted(propertyPath, envVar));
        }
    }
}