package com.whatsappbot.application.template;

public record TemplateSummary(
        String templateCode,
        String metaTemplateName,
        String languageCode,
        String category,
        String audience,
        String description,
        String bodyPreview,
        String componentSchemaJson,
        boolean enabledForAi
) {
}
