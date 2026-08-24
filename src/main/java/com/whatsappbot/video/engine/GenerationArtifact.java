package com.whatsappbot.video.engine;

import java.util.Map;

/**
 * Output produced by one adapter and consumed by later stages.
 *
 * value is intentionally generic: it can be script text, a local path, an
 * object-storage key, or a provider reference depending on the artifact type.
 */
public record GenerationArtifact(
        GenerationArtifactType type,
        String value,
        String provider,
        Map<String, String> metadata
) {
    public GenerationArtifact {
        if (type == null) {
            throw new IllegalArgumentException("artifact type is required");
        }
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("artifact value is required");
        }
        provider = provider == null || provider.isBlank() ? "internal" : provider.trim();
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
