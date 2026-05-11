package com.whatsappbot.infrastructure.whatsapp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.application.template.TemplateSendResult;
import com.whatsappbot.domain.template.WhatsAppMessageTemplate;
import com.whatsappbot.domain.tenant.TenantEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppTemplateGraphClient {

    private final ObjectMapper objectMapper;
    private final WhatsAppTemplatePayloadBuilder payloadBuilder;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${whatsapp.graph-api-version:v19.0}")
    private String graphApiVersion;

    @Value("${whatsapp.fallback-access-token:}")
    private String fallbackAccessToken;

    @Value("${whatsapp.mock-send-enabled:false}")
    private boolean mockSendEnabled;

    public TemplateSendResult sendTemplate(TenantEntity tenant,
                                           WhatsAppMessageTemplate template,
                                           String toPhoneNumber,
                                           String componentsJson,
                                           List<String> bodyTextParameters) {
        String url = "https://graph.facebook.com/" + graphApiVersion + "/" + tenant.getPhoneNumberId() + "/messages";
        Map<String, Object> payload = payloadBuilder.buildPayload(template, toPhoneNumber, componentsJson, bodyTextParameters);
        String payloadJson = toJson(payload);

        if (mockSendEnabled) {
            log.info("MOCK WhatsApp template send. tenant={}, template={}, to={}, payload={}",
                    tenant.getTenantCode(), template.getTemplateCode(), toPhoneNumber, payloadJson);

            return new TemplateSendResult(
                    true,
                    200,
                    "{\"mock\":true,\"message\":\"Template send skipped locally\"}",
                    payloadJson
            );
        }
        String accessToken = resolveAccessToken(tenant);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payloadJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean sent = response.statusCode() < 300;
            if (!sent) {
                log.warn("WhatsApp template send failed. tenant={}, template={}, status={}, body={}",
                        tenant.getTenantCode(), template.getTemplateCode(), response.statusCode(), response.body());
            }
            return new TemplateSendResult(sent, response.statusCode(), response.body(), payloadJson);
        } catch (IOException e) {
            throw new IllegalStateException("WhatsApp template send failed due to IO error", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("WhatsApp template send was interrupted", e);
        }
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

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize WhatsApp template payload", e);
        }
    }
}
