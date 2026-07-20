package com.whatsappbot.reels;

import java.time.LocalDateTime;
import java.util.UUID;

public record ReelJobResponse(
        UUID id,
        UUID videoScriptId,
        String title,
        String templateCode,
        ReelRenderStatus status,
        boolean includeVoice,
        String voice,
        Long outputSizeBytes,
        String errorMessage,
        int attempts,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime completedAt,
        String downloadUrl
) {}
