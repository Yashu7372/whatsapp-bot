package com.whatsappbot.video.engine;

import java.util.List;

public record PipelineStep(
        GenerationState from,
        GenerationState to,
        List<GenerationCapability> requiredCapabilities,
        List<GenerationCapability> optionalCapabilities
) {
    public PipelineStep {
        requiredCapabilities = requiredCapabilities == null ? List.of() : List.copyOf(requiredCapabilities);
        optionalCapabilities = optionalCapabilities == null ? List.of() : List.copyOf(optionalCapabilities);
    }

    public static List<PipelineStep> defaultPipeline() {
        return List.of(
                new PipelineStep(GenerationState.INTAKE, GenerationState.CONTENT_LOCKED,
                        List.of(GenerationCapability.CONTENT), List.of()),
                new PipelineStep(GenerationState.CONTENT_LOCKED, GenerationState.AUDIO_LOCKED,
                        List.of(GenerationCapability.AUDIO), List.of(GenerationCapability.SPEECH_ALIGNMENT)),
                new PipelineStep(GenerationState.AUDIO_LOCKED, GenerationState.VISUAL_PLAN_LOCKED,
                        List.of(GenerationCapability.VISUAL_PLAN), List.of()),
                new PipelineStep(GenerationState.VISUAL_PLAN_LOCKED, GenerationState.PRESENTER_GENERATED,
                        List.of(GenerationCapability.PRESENTER), List.of(GenerationCapability.LIP_SYNC)),
                new PipelineStep(GenerationState.PRESENTER_GENERATED, GenerationState.COMPOSITION_CHECKED,
                        List.of(GenerationCapability.COMPOSITION), List.of()),
                new PipelineStep(GenerationState.COMPOSITION_CHECKED, GenerationState.RENDERED,
                        List.of(GenerationCapability.RENDER), List.of()),
                new PipelineStep(GenerationState.RENDERED, GenerationState.VERIFIED,
                        List.of(GenerationCapability.VERIFY), List.of())
        );
    }
}
