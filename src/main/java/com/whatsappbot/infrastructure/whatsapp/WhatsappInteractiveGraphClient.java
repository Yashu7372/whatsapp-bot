package com.whatsappbot.infrastructure.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.domain.tenant.TenantEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;

@Component
public class WhatsappInteractiveGraphClient {

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    @Value("${whatsapp.graph-api-version:v19.0}")
    private String graphApiVersion;

    @Value("${whatsapp.mock-send-enabled:false}")
    private boolean mockSendEnabled;

    @Value("${whatsapp.fallback-access-token:}")
    private String fallbackAccessToken;

    public WhatsappInteractiveGraphClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.webClient = WebClient.builder()
                .baseUrl("https://graph.facebook.com")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public JsonNode sendRawMessage(TenantEntity tenant, Map<String, Object> payload) {
        if (mockSendEnabled) {
            return objectMapper.valueToTree(Map.of(
                    "mock", true,
                    "tenant", tenant.getTenantCode(),
                    "phone_number_id", tenant.getPhoneNumberId(),
                    "payload", payload
            ));
        }

        return webClient.post()
                .uri("/{version}/{phoneNumberId}/messages", graphApiVersion, tenant.getPhoneNumberId())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + resolveAccessToken(tenant))
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
    }

    public JsonNode sendText(TenantEntity tenant, String to, String body) {
        return sendRawMessage(tenant, Map.of(
                "messaging_product", "whatsapp",
                "to", to,
                "type", "text",
                "text", Map.of("body", body)
        ));
    }

    private String resolveAccessToken(TenantEntity tenant) {
        if (tenant.getAccessTokenEncrypted() != null && !tenant.getAccessTokenEncrypted().isBlank()) {
            return tenant.getAccessTokenEncrypted();
        }
        if (fallbackAccessToken == null || fallbackAccessToken.isBlank()) {
            throw new IllegalStateException("No WhatsApp access token configured for tenant " + tenant.getTenantCode());
        }
        return fallbackAccessToken;
    }
}