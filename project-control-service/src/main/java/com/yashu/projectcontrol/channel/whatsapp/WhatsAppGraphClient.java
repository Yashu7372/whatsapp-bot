package com.yashu.projectcontrol.channel.whatsapp;

import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Component
public class WhatsAppGraphClient {

    private static final int MAX_TEXT_LENGTH = 4000;

    private final WhatsAppChannelProperties properties;

    public WhatsAppGraphClient(WhatsAppChannelProperties properties) {
        this.properties = properties;
    }

    public void sendText(String destination, String body) {
        properties.requireSendConfiguration();
        String to = WhatsAppChannelProperties.normalizeAddress(destination);
        if (to.isBlank()) throw new IllegalArgumentException("WhatsApp destination is required");
        String text = body == null ? "" : body.trim();
        if (text.isBlank()) throw new IllegalArgumentException("WhatsApp message body is required");
        if (text.length() > MAX_TEXT_LENGTH) {
            text = text.substring(0, MAX_TEXT_LENGTH - 3) + "...";
        }

        RestClient client = RestClient.builder()
                .baseUrl("https://graph.facebook.com")
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.accessToken())
                .build();
        client.post()
                .uri("/{version}/{phoneNumberId}/messages", properties.apiVersion(), properties.phoneNumberId())
                .body(Map.of(
                        "messaging_product", "whatsapp",
                        "recipient_type", "individual",
                        "to", to,
                        "type", "text",
                        "text", Map.of("preview_url", false, "body", text)))
                .retrieve()
                .toBodilessEntity();
    }
}
