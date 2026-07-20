package com.whatsappbot.infrastructure.ai;

import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ai")
public class AiProperties {

    @NotNull
    private AiProvider provider = AiProvider.GEMINI;
    private double temperature = 0.0;
    private int timeoutSeconds = 30;
    private int memoryMaxMessages = 20;

    @NestedConfigurationProperty
    private final Gemini gemini = new Gemini();
    @NestedConfigurationProperty
    private final Ollama ollama = new Ollama();
    @NestedConfigurationProperty
    private final OpenAi openai = new OpenAi();
    @NestedConfigurationProperty
    private final Anthropic anthropic = new Anthropic();

    public AiProvider getProvider() { return provider; }
    public void setProvider(AiProvider provider) { this.provider = provider; }
    public double getTemperature() { return temperature; }
    public void setTemperature(double temperature) { this.temperature = temperature; }
    public int getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(int timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
    public int getMemoryMaxMessages() { return memoryMaxMessages; }
    public void setMemoryMaxMessages(int memoryMaxMessages) { this.memoryMaxMessages = memoryMaxMessages; }
    public Gemini getGemini() { return gemini; }
    public Ollama getOllama() { return ollama; }
    public OpenAi getOpenai() { return openai; }
    public Anthropic getAnthropic() { return anthropic; }

    public static class Gemini {
        private String apiKey;
        private String modelName = "gemini-2.5-flash";
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
    }

    public static class Ollama {
        private String baseUrl = "http://localhost:11434";
        private String modelName = "gemma4:e2b-it-qat";
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
    }

    public static class OpenAi {
        private String apiKey;
        private String modelName = "gpt-4o-mini";
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
    }

    public static class Anthropic {
        private String apiKey;
        private String modelName = "claude-sonnet-4-5";
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getModelName() { return modelName; }
        public void setModelName(String modelName) { this.modelName = modelName; }
    }
}
