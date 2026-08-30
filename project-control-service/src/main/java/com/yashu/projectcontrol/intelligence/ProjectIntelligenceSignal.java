package com.yashu.projectcontrol.intelligence;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Lightweight domain signal consumed by Project Control intelligence collectors.
 *
 * <p>The signal is not authoritative project truth. It points at an already committed
 * business resource/event and gives collectors enough context to derive append-only
 * features or findings without scanning an entire project.</p>
 */
public record ProjectIntelligenceSignal(
        UUID projectId,
        UUID scopeId,
        String triggerType,
        String subjectType,
        UUID subjectId,
        String triggerKey,
        String payloadJson,
        Instant occurredAt) {

    public ProjectIntelligenceSignal {
        projectId = Objects.requireNonNull(projectId, "projectId is required");
        triggerType = normalizeCode(triggerType, "triggerType");
        subjectType = normalizeCode(subjectType, "subjectType");
        triggerKey = requireText(triggerKey, "triggerKey");
        payloadJson = payloadJson == null || payloadJson.isBlank() ? "{}" : payloadJson.trim();
        occurredAt = occurredAt == null ? Instant.now() : occurredAt;
    }

    private static String normalizeCode(String value, String field) {
        return requireText(value, field).toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }
}
