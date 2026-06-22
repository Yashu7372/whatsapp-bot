package com.whatsappbot.platform.core.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record AnalyticsRequest(
        UUID tenantId,
        String platformCode,
        String externalPublishId,
        LocalDateTime since,
        LocalDateTime until
) {}
