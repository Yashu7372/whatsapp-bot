package com.whatsappbot.infrastructure.whatsapp;

import com.fasterxml.jackson.databind.JsonNode;
import com.whatsappbot.domain.message.MessageType;

public record WhatsAppInboundMessage(
        String phoneNumberId,
        String fromWaId,
        String fromPhoneNumber,
        String displayName,
        String waMessageId,
        MessageType messageType,
        String textBody,
        String rawPayload,
        JsonNode messageNode
) {
}