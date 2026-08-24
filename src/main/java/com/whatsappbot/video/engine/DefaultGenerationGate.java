package com.whatsappbot.video.engine;

import org.springframework.stereotype.Component;

/**
 * Small default gate set. Provider-specific quality checks can be added as
 * additional GenerationGate beans without changing the engine.
 */
@Component
public class DefaultGenerationGate implements GenerationGate {

    @Override
    public String name() {
        return "default-artifact-gate";
    }

    @Override
    public boolean supports(GenerationState targetState, GenerationContext context) {
        return targetState != GenerationState.INTAKE && targetState != GenerationState.FAILED;
    }

    @Override
    public GateResult validate(GenerationState targetState, GenerationContext context) {
        return switch (targetState) {
            case CONTENT_LOCKED -> require(context, GenerationArtifactType.SCRIPT,
                    "CONTENT_MISSING", "Content stage must produce a non-empty script.");
            case AUDIO_LOCKED -> validateAudio(context);
            case VISUAL_PLAN_LOCKED -> require(context, GenerationArtifactType.VISUAL_PLAN,
                    "VISUAL_PLAN_MISSING", "Visual planning must produce a visual plan.");
            case PRESENTER_GENERATED -> validatePresenter(context);
            case COMPOSITION_CHECKED -> require(context, GenerationArtifactType.COMPOSITION_PLAN,
                    "COMPOSITION_MISSING", "Composition stage must produce a composition plan.");
            case RENDERED -> require(context, GenerationArtifactType.FINAL_VIDEO,
                    "VIDEO_MISSING", "Render stage must produce the final video.");
            case VERIFIED -> validateQa(context);
            default -> GateResult.pass("No validation required.");
        };
    }

    private GateResult validateAudio(GenerationContext context) {
        GenerationArtifact audio = context.artifact(GenerationArtifactType.NARRATION_AUDIO).orElse(null);
        if (audio == null) {
            return GateResult.fail("AUDIO_MISSING", "Audio stage must produce narration audio.");
        }
        String duration = audio.metadata().get("durationSeconds");
        if (duration == null || duration.isBlank()) {
            return GateResult.fail("AUDIO_DURATION_MISSING",
                    "Narration audio must report its measured duration so it can become the master timeline.");
        }
        try {
            if (Double.parseDouble(duration) <= 0) {
                return GateResult.fail("AUDIO_DURATION_INVALID", "Narration duration must be greater than zero.");
            }
        } catch (NumberFormatException e) {
            return GateResult.fail("AUDIO_DURATION_INVALID", "Narration duration is not numeric.");
        }
        return GateResult.pass("Narration audio is locked as the master timeline.");
    }

    private GateResult validatePresenter(GenerationContext context) {
        if (context.mode() == GenerationMode.FACELESS) {
            return GateResult.pass("Presenter generation is not required for faceless mode.");
        }
        if (context.hasArtifact(GenerationArtifactType.LIP_SYNCED_PRESENTER)
                || context.hasArtifact(GenerationArtifactType.PRESENTER_VIDEO)) {
            return GateResult.pass("Presenter output is available.");
        }
        return GateResult.fail("PRESENTER_MISSING",
                "Presenter or dialogue mode requires a presenter video before composition.");
    }

    private GateResult validateQa(GenerationContext context) {
        GenerationArtifact report = context.artifact(GenerationArtifactType.QA_REPORT).orElse(null);
        if (report == null) {
            return GateResult.fail("QA_REPORT_MISSING", "Verification must produce a QA report.");
        }
        if (!Boolean.parseBoolean(report.metadata().getOrDefault("passed", "false"))) {
            return GateResult.fail("QA_FAILED", "QA report did not pass all required checks.");
        }
        return GateResult.pass("Video passed final QA.");
    }

    private GateResult require(GenerationContext context, GenerationArtifactType type,
                               String code, String message) {
        return context.artifact(type)
                .map(artifact -> GateResult.pass(type + " is available."))
                .orElseGet(() -> GateResult.fail(code, message));
    }
}
