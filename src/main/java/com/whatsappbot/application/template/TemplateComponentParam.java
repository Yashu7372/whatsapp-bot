package com.whatsappbot.application.template;

public record TemplateComponentParam(
        String type,
        String text,
        String currencyCode,
        Long amount1000,
        String dateTimeFallback,
        String mediaLink,
        String mediaId,
        String payload
) {
    public static TemplateComponentParam text(String value) {
        return new TemplateComponentParam("text", value, null, null, null, null, null, null);
    }
}
