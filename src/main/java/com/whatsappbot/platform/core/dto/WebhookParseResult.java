package com.whatsappbot.platform.core.dto;

import java.util.Map;

public record WebhookParseResult(
        boolean handled,
        String inboundMessage,
        Map<String, String> metadata
) {
    public static WebhookParseResult notHandled() {
        return new WebhookParseResult(false, null, Map.of());
    }

    public static WebhookParseResult handled(String inboundMessage) {
        return new WebhookParseResult(true, inboundMessage, Map.of());
    }
}
