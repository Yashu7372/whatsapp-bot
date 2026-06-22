package com.whatsappbot.platform.core.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record PublishCommand(
        UUID tenantId,
        String platformCode,
        UUID platformAccountId,
        String contentJson,
        LocalDateTime scheduledAt
) {}
