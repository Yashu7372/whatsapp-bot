package com.yashu.projectcontrol.assistant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/**
 * Thin OpenAI-compatible adapter. This is deliberately not an agent: no tools,
 * no database access and no workflow commands are exposed to the model.
 */
@Component
@ConditionalOnProperty(name = "project-control.ai.enabled", havingValue = "true")
public class OpenAiCompatibleProjectControlReasoningWorker implements ProjectControlReasoningWorker {

    private static final String SYSTEM_PROMPT = """
            You are a reasoning worker inside an enterprise Project Control application.
            You are NOT the source of truth and you have no authority to approve, reject,
            certify, value, pay, modify workflow state, or invent project facts.

            Use only the authorized context JSON supplied in this request.
            Distinguish controlled business facts from extractor-derived evidence.
            If extractor evidence is absent, incomplete or uncertain, say so.
            A review comment is not proof that the submitter complied with it.
            Never infer approval/compliance from a document reference alone.
            Keep the answer concise enough for a reviewer notification or WhatsApp chat.
            When useful, explain why the current actor received this work and cite the
            document revision, workflow step or evidence snapshot identifiers present
            in the context.
            """;

    private final RestClient restClient;
    private final String model;

    public OpenAiCompatibleProjectControlReasoningWorker(
            @Value("${project-control.ai.base-url:https://api.openai.com/v1}") String baseUrl,
            @Value("${project-control.ai.api-key:}") String apiKey,
            @Value("${project-control.ai.model:gpt-5-mini}") String model) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "project-control.ai.enabled=true requires PROJECT_CONTROL_AI_API_KEY/project-control.ai.api-key");
        }
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey.trim())
                .build();
        this.model = model;
    }

    @Override
    public String reason(ReasoningRequest request) {
        String userPrompt = "Task: " + request.task()
                + "\nQuestion: " + (request.question() == null ? "" : request.question())
                + "\n\nAuthorized context JSON:\n" + request.authorizedContextJson();

        JsonNode response = restClient.post()
                .uri("/chat/completions")
                .body(Map.of(
                        "model", model,
                        "messages", List.of(
                                Map.of("role", "system", "content", SYSTEM_PROMPT),
                                Map.of("role", "user", "content", userPrompt))))
                .retrieve()
                .body(JsonNode.class);

        if (response == null
                || response.get("choices") == null
                || !response.get("choices").isArray()
                || response.get("choices").size() == 0
                || response.get("choices").get(0).get("message") == null
                || response.get("choices").get(0).get("message").get("content") == null) {
            throw new IllegalStateException("Reasoning provider returned no message content");
        }
        String text = response.get("choices").get(0).get("message").get("content").asString();
        if (text == null || text.isBlank()) {
            throw new IllegalStateException("Reasoning provider returned an empty message");
        }
        return text.trim();
    }
}
