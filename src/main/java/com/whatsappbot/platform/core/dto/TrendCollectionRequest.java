package com.whatsappbot.platform.core.dto;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record TrendCollectionRequest(
        UUID tenantId,
        String platformCode,
        LocalDateTime since,
        Map<String, String> metadata
) {}
