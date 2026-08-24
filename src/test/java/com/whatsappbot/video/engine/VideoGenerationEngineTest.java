package com.whatsappbot.video.engine;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VideoGenerationEngineTest {

    @Test
    void facelessPipelineRunsWithoutPresenterAdapter() {
        VideoGenerationEngine engine = new VideoGenerationEngine(
                happyPathAdapters(true),
                List.of(new DefaultGenerationGate())
        );
        GenerationContext context = context(GenerationMode.FACELESS);
        GenerationState state = GenerationState.INTAKE;

        while (state != GenerationState.VERIFIED) {
            VideoGenerationEngine.ExecutionResult result = engine.executeNext(state, context);
            state = result.state();
            context = result.context();
        }

        assertEquals(GenerationState.VERIFIED, state);
        assertTrue(context.hasArtifact(GenerationArtifactType.SCRIPT));
        assertTrue(context.hasArtifact(GenerationArtifactType.NARRATION_AUDIO));
        assertTrue(context.hasArtifact(GenerationArtifactType.VISUAL_PLAN));
        assertTrue(context.hasArtifact(GenerationArtifactType.COMPOSITION_PLAN));
        assertTrue(context.hasArtifact(GenerationArtifactType.FINAL_VIDEO));
        assertTrue(context.hasArtifact(GenerationArtifactType.QA_REPORT));
        assertFalse(context.hasArtifact(GenerationArtifactType.PRESENTER_VIDEO));
    }

    @Test
    void presenterModeBlocksWhenNoPresenterAdapterExists() {
        VideoGenerationEngine engine = new VideoGenerationEngine(
                happyPathAdapters(true),
                List.of(new DefaultGenerationGate())
        );
        GenerationContext context = context(GenerationMode.PRESENTER)
                .withArtifacts(List.of(
                        artifact(GenerationArtifactType.SCRIPT, "script"),
                        new GenerationArtifact(GenerationArtifactType.NARRATION_AUDIO, "/tmp/audio.wav", "test",
                                Map.of("durationSeconds", "12.5")),
                        artifact(GenerationArtifactType.VISUAL_PLAN, "[{}]")
                ));

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> engine.executeNext(GenerationState.VISUAL_PLAN_LOCKED, context)
        );
        assertTrue(error.getMessage().contains("PRESENTER"));
    }

    @Test
    void qaGateRejectsFailedVerification() {
        List<GenerationAdapter> adapters = happyPathAdapters(false);
        VideoGenerationEngine engine = new VideoGenerationEngine(
                adapters,
                List.of(new DefaultGenerationGate())
        );
        GenerationContext context = context(GenerationMode.FACELESS)
                .withArtifacts(List.of(artifact(GenerationArtifactType.FINAL_VIDEO, "/tmp/final.mp4")));

        GateRejectedException error = assertThrows(
                GateRejectedException.class,
                () -> engine.executeNext(GenerationState.RENDERED, context)
        );
        assertEquals(GenerationState.VERIFIED, error.targetState());
        assertEquals("QA_FAILED", error.result().code());
        assertTrue(error.rejectedContext().hasArtifact(GenerationArtifactType.QA_REPORT));
    }

    private List<GenerationAdapter> happyPathAdapters(boolean qaPassed) {
        List<GenerationAdapter> adapters = new ArrayList<>();
        adapters.add(adapter(GenerationCapability.CONTENT,
                artifact(GenerationArtifactType.SCRIPT, "This is the script.")));
        adapters.add(adapter(GenerationCapability.AUDIO,
                new GenerationArtifact(GenerationArtifactType.NARRATION_AUDIO, "/tmp/audio.wav", "test",
                        Map.of("durationSeconds", "12.5"))));
        adapters.add(adapter(GenerationCapability.VISUAL_PLAN,
                artifact(GenerationArtifactType.VISUAL_PLAN, "[{}]")));
        adapters.add(adapter(GenerationCapability.COMPOSITION,
                artifact(GenerationArtifactType.COMPOSITION_PLAN, "plan")));
        adapters.add(adapter(GenerationCapability.RENDER,
                artifact(GenerationArtifactType.FINAL_VIDEO, "/tmp/final.mp4")));
        adapters.add(adapter(GenerationCapability.VERIFY,
                new GenerationArtifact(GenerationArtifactType.QA_REPORT, "qa", "test",
                        Map.of("passed", Boolean.toString(qaPassed)))));
        return adapters;
    }

    private GenerationAdapter adapter(GenerationCapability capability, GenerationArtifact artifact) {
        return new GenerationAdapter() {
            @Override
            public GenerationCapability capability() {
                return capability;
            }

            @Override
            public String name() {
                return "test-" + capability.name().toLowerCase();
            }

            @Override
            public StageResult generate(GenerationContext context) {
                return StageResult.of(artifact, "ok");
            }
        };
    }

    private GenerationArtifact artifact(GenerationArtifactType type, String value) {
        return new GenerationArtifact(type, value, "test", Map.of());
    }

    private GenerationContext context(GenerationMode mode) {
        return new GenerationContext(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Test topic",
                mode,
                "INSTAGRAM",
                30,
                Map.of(),
                List.of()
        );
    }
}
