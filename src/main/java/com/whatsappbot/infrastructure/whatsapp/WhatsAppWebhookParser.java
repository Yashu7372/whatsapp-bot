package com.whatsappbot.infrastructure.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.whatsappbot.domain.message.MessageType;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class WhatsAppWebhookParser {

    public Optional<WhatsAppInboundMessage> parseFirstMessage(JsonNode payload) {
        JsonNode value = payload.at("/entry/0/changes/0/value");
        JsonNode messages = value.get("messages");

        if (messages == null || !messages.isArray() || messages.isEmpty()) {
            return Optional.empty();
        }

        JsonNode message = messages.get(0);
        String phoneNumberId = value.at("/metadata/phone_number_id").asText(null);
        String fromWaId = message.path("from").asText(null);
        String messageId = message.path("id").asText(null);
        String displayName = value.at("/contacts/0/profile/name").asText(null);
        String type = message.path("type").asText("unknown");
        MessageType messageType = mapMessageType(type);
        String textBody = extractText(message, type);

        if (phoneNumberId == null || fromWaId == null) {
            throw new IllegalArgumentException("Webhook payload is missing phone_number_id or from wa_id");
        }

        return Optional.of(new WhatsAppInboundMessage(
                phoneNumberId,
                fromWaId,
                fromWaId,
                displayName,
                messageId,
                messageType,
                textBody,
                payload.toString(),
                message
        ));
    }

    private String extractText(JsonNode message, String type) {
        if ("text".equals(type)) {
            return message.at("/text/body").asText("");
        }
        if ("interactive".equals(type)) {
            String buttonText = message.at("/interactive/button_reply/title").asText(null);
            if (buttonText != null) {
                return buttonText;
            }
            return message.at("/interactive/list_reply/title").asText("");
        }
        if ("order".equals(type)) {
            return "Customer submitted a WhatsApp cart order.";
        }
        return "";
    }

    private MessageType mapMessageType(String type) {
        return switch (type) {
            case "text" -> MessageType.TEXT;
            case "image" -> MessageType.IMAGE;
            case "video" -> MessageType.VIDEO;
            case "audio" -> MessageType.AUDIO;
            case "document" -> MessageType.DOCUMENT;
            case "interactive" -> MessageType.INTERACTIVE;
            case "order" -> MessageType.ORDER;
            default -> MessageType.UNKNOWN;
        };
    }
}
