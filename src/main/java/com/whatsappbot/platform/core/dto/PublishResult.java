package com.whatsappbot.platform.core.dto;

public record PublishResult(
        boolean success,
        String externalPublishId,
        String errorMessage
) {
    public static PublishResult ok(String externalPublishId) {
        return new PublishResult(true, externalPublishId, null);
    }

    public static PublishResult failed(String errorMessage) {
        return new PublishResult(false, null, errorMessage);
    }
}
