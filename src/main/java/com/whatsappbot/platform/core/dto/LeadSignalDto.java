package com.whatsappbot.platform.core.dto;

import java.util.Map;

public record LeadSignalDto(
        String externalId,
        String sourceType,
        String signalType,
        String messageText,
        Map<String, String> metadata
) {}
