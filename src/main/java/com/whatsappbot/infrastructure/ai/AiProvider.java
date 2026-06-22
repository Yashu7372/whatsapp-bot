package com.whatsappbot.infrastructure.ai;

/**
 * Supported chat-model backends. Selecting a provider is a config change
 * ({@code ai.provider} / env var {@code AI_PROVIDER}) — never a code change.
 * <p>
 * Adding a new provider means: add the enum value, add its dependency to
 * pom.xml, add a {@code build(...)} branch in {@link ChatModelFactory}, and
 * add its provider-specific properties to {@link AiProperties}. No existing
 * tenant-facing or AI-service code changes.
 */
public enum AiProvider {

    /**
     * Gemini via the direct Generative Language API (ai.google.dev),
     * authenticated with a simple API key. Has a free, rate-limited tier —
     * distinct from Vertex AI, which requires a billed GCP project and
     * Application Default Credentials.
     */
    GEMINI,

    /**
     * Fully local inference via Ollama (https://ollama.com). No API key,
     * no per-token cost, no external network call — the model runs on
     * whatever host the Ollama daemon is reachable from (the Docker host,
     * a container on the same network, or a remote box).
     */
    OLLAMA,

    /**
     * OpenAI's hosted API. Paid, included for when the platform has
     * paying tenants and wants OpenAI-quality models as an option.
     */
    OPENAI,

    /**
     * Anthropic's hosted API. Paid, same rationale as OPENAI.
     */
    ANTHROPIC
}