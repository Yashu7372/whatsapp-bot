package com.whatsappbot.application.template;

public record TemplateSendResult(
        boolean sent,
        int statusCode,
        String responseBody,
        String graphPayloadJson
) {
}
