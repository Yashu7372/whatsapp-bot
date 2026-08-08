package com.whatsappbot.infrastructure.whatsapp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class WhatsAppGraphClient {

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Value("${whatsapp.mock-send-enabled:false}")
    private boolean mockSendEnabled;

    @Value("${whatsapp.graph-api-version:v19.0}")
    private String graphApiVersion;

    @Value("${whatsapp.fallback-access-token:}")
    private String fallbackAccessToken;

    public void sendTextMessage(TenantEntity tenant, String toPhoneNumber, String messageText) {
        try {
            sendTextMessageChecked(tenant,toPhoneNumber,messageText);
        } catch (RuntimeException ex) {
            log.error("WhatsApp send failed. tenant={}, to={}",tenant.getTenantCode(),toPhoneNumber,ex);
        }
    }

    /** Same transport as the conversational reply path, but failures are surfaced so durable
     * notification workers can retry instead of treating a logged HTTP failure as success. */
    public void sendTextMessageChecked(TenantEntity tenant, String toPhoneNumber, String messageText) {
        String url = "https://graph.facebook.com/" + graphApiVersion + "/" + tenant.getPhoneNumberId() + "/messages";
        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", toPhoneNumber,
                "type", "text",
                "text", Map.of("preview_url", false,"body", messageText)
        );

        if (mockSendEnabled) {
            log.info("MOCK WhatsApp text send. tenant={}, to={}, payload={}",tenant.getTenantCode(),toPhoneNumber,toJson(payload));
            return;
        }

        String accessToken = resolveAccessToken(tenant);
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(toJson(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 300) {
                throw new IllegalStateException("WhatsApp Graph API returned HTTP "+response.statusCode()+": "+response.body());
            }
            log.info("WhatsApp message sent. tenant={}, to={}", tenant.getTenantCode(), toPhoneNumber);
        } catch (IOException e) {
            throw new IllegalStateException("WhatsApp send failed due to IO error",e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("WhatsApp send interrupted",e);
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
        try { return objectMapper.writeValueAsString(payload); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Failed to serialize WhatsApp payload", e); }
    }
}
