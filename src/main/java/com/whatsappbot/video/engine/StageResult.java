package com.whatsappbot.video.engine;

import java.util.List;

public record StageResult(
        List<GenerationArtifact> artifacts,
        String message
) {
    public StageResult {
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        message = message == null ? "" : message;
    }

    public static StageResult of(GenerationArtifact artifact, String message) {
        return new StageResult(List.of(artifact), message);
    }

    public static StageResult empty(String message) {
        return new StageResult(List.of(), message);
    }
}
