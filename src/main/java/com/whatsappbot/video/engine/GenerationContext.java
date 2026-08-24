package com.whatsappbot.video.engine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Immutable context passed through every stage.
 */
public record GenerationContext(
        UUID generationId,
        UUID tenantId,
        String topic,
        GenerationMode mode,
        String platform,
        int targetDurationSeconds,
        Map<String, String> options,
        List<GenerationArtifact> artifacts
) {
    public GenerationContext {
        if (generationId == null) throw new IllegalArgumentException("generationId is required");
        if (tenantId == null) throw new IllegalArgumentException("tenantId is required");
        if (topic == null || topic.isBlank()) throw new IllegalArgumentException("topic is required");
        mode = mode == null ? GenerationMode.FACELESS : mode;
        platform = platform == null || platform.isBlank() ? "INSTAGRAM" : platform.trim();
        targetDurationSeconds = Math.max(5, Math.min(targetDurationSeconds, 90));
        options = options == null ? Map.of() : Map.copyOf(options);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    public Optional<GenerationArtifact> artifact(GenerationArtifactType type) {
        for (int i = artifacts.size() - 1; i >= 0; i--) {
            GenerationArtifact artifact = artifacts.get(i);
            if (artifact.type() == type) {
                return Optional.of(artifact);
            }
        }
        return Optional.empty();
    }

    public boolean hasArtifact(GenerationArtifactType type) {
        return artifact(type).isPresent();
    }

    public GenerationContext withArtifacts(List<GenerationArtifact> additions) {
        if (additions == null || additions.isEmpty()) {
            return this;
        }
        List<GenerationArtifact> merged = new ArrayList<>(artifacts);
        merged.addAll(additions);
        return new GenerationContext(
                generationId,
                tenantId,
                topic,
                mode,
                platform,
                targetDurationSeconds,
                options,
                merged
        );
    }

    public String option(String key, String fallback) {
        String value = options.get(key);
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
