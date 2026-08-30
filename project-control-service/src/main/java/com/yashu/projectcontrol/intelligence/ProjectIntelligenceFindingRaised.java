package com.yashu.projectcontrol.intelligence;

import java.time.Instant;
import java.util.UUID;

/**
 * Raised only after an evidence-derived finding has been persisted. Future bounded
 * analysis/notification capabilities may subscribe to this event; the event itself
 * grants no workflow or financial authority.
 */
public record ProjectIntelligenceFindingRaised(
        UUID findingId,
        UUID projectId,
        UUID scopeId,
        String subjectType,
        UUID subjectId,
        String findingCode,
        String severity,
        double confidence,
        Instant raisedAt) {
}
