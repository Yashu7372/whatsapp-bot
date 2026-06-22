package com.whatsappbot.infrastructure.ai;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

/**
 * Single typed config surface for the pluggable AI layer. Binds the
 * {@code ai.*} property tree (env vars: {@code AI_PROVIDER},
 * {@code AI_GEMINI_API_KEY}, {@code AI_OLLAMA_BASE_URL}, etc.).
 * <p>
 * Per the no-hardcoding rule, this is the only place provider selection and
 * provider credentials/endpoints are read — {@link ChatModelFactory} consumes
 * this bean, nothing reaches into {@code @Value} directly for AI config.
 */
@Validated
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    /**
     * Which backend builds the {@link dev.langchain4j.model.chat.ChatModel}
     * bean at startup. Change this + restart the container to switch
     * providers — no code change, no rebuild.
     */
    @NotNull
    private AiProvider provider = AiProvider.GEMINI;

    /** Shared across all providers, per product decision: one knob, not per-provider duplication. */
    private double temperature = 0.0;

    /** Shared request timeout, in seconds, applied to whichever provider is active. */
    private int timeoutSeconds = 30;

    /** Chat memory window, already in place — kept here for a single AI config surface. */
    private int memoryMaxMessages = 20;

    @NestedConfigurationProperty
    private final Gemini gemini = new Gemini();

    @NestedConfigurationProperty
    private final Ollama ollama = new Ollama();

    @NestedConfigurationProperty
    private final OpenAi openai = new OpenAi();

    @NestedConfigurationProperty
    private final Anthropic anthropic = new Anthropic();

    public AiProvider getProvider() {
        return provider;
    }

    public void setProvider(AiProvider provider) {
        this.provider = provider;
    }

    public double getTemperature() {
        return temperature;
    }

    public void setTemperature(double temperature) {
        this.temperature = temperature;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public int getMemoryMaxMessages() {
        return memoryMaxMessages;
    }

    public void setMemoryMaxMessages(int memoryMaxMessages) {
        this.memoryMaxMessages = memoryMaxMessages;
    }

    public Gemini getGemini() {
        return gemini;
    }

    public Ollama getOllama() {
        return ollama;
    }

    public OpenAi getOpenai() {
        return openai;
    }

    public Anthropic getAnthropic() {
        return anthropic;
    }

    /** Gemini direct API (ai.google.dev) — free tier, API-key auth, NOT Vertex AI. */
    public static class Gemini {
        private String apiKey;
        private String modelName = "gemini-2.5-flash";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }
    }

    /** Local Ollama daemon — no API key; base URL points at the Ollama container/host. */
    public static class Ollama {
        private String baseUrl = "http://localhost:11434";
        private String modelName = "llama3.1:8b";

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }
    }

    /** OpenAI hosted API. */
    public static class OpenAi {
        private String apiKey;
        private String modelName = "gpt-4o-mini";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }
    }

    /** Anthropic hosted API. */
    public static class Anthropic {
        private String apiKey;
        private String modelName = "claude-sonnet-4-5";

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getModelName() {
            return modelName;
        }

        public void setModelName(String modelName) {
            this.modelName = modelName;
        }
    }
}