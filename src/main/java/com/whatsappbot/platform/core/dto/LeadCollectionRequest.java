package com.whatsappbot.platform.core.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record LeadCollectionRequest(
        UUID tenantId,
        String platformCode,
        LocalDateTime since
) {}
