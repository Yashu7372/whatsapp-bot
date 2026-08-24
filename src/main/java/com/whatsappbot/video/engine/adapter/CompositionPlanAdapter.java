package com.whatsappbot.video.engine.adapter;

import com.whatsappbot.video.engine.GenerationAdapter;
import com.whatsappbot.video.engine.GenerationArtifact;
import com.whatsappbot.video.engine.GenerationArtifactType;
import com.whatsappbot.video.engine.GenerationCapability;
import com.whatsappbot.video.engine.GenerationContext;
import com.whatsappbot.video.engine.GenerationMode;
import com.whatsappbot.video.engine.StageResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds a small deterministic composition manifest from already-gated outputs.
 * The renderer consumes the artifacts; this class does not render video.
 */
@Component
public class CompositionPlanAdapter implements GenerationAdapter {

    @Override
    public GenerationCapability capability() {
        return GenerationCapability.COMPOSITION;
    }

    @Override
    public String name() {
        return "composition-plan";
    }

    @Override
    public StageResult generate(GenerationContext context) {
        GenerationArtifact audio = context.artifact(GenerationArtifactType.NARRATION_AUDIO)
                .orElseThrow(() -> new IllegalStateException("Narration audio is required before composition."));
        GenerationArtifact visualPlan = context.artifact(GenerationArtifactType.VISUAL_PLAN)
                .orElseThrow(() -> new IllegalStateException("Visual plan is required before composition."));

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("audio", audio.value());
        metadata.put("visualPlan", visualPlan.value());
        metadata.put("durationSeconds", audio.metadata().getOrDefault("durationSeconds", ""));
        metadata.put("templateCode", context.option("templateCode", "PRODUCT_HOOK_V1"));
        metadata.put("brandName", context.option("brandName", ""));
        metadata.put("callToAction", context.option("callToAction", ""));

        if (context.mode() != GenerationMode.FACELESS) {
            context.artifact(GenerationArtifactType.LIP_SYNCED_PRESENTER)
                    .or(() -> context.artifact(GenerationArtifactType.PRESENTER_VIDEO))
                    .ifPresent(presenter -> metadata.put("presenter", presenter.value()));
        }

        GenerationArtifact plan = new GenerationArtifact(
                GenerationArtifactType.COMPOSITION_PLAN,
                context.generationId().toString(),
                "composition-plan",
                metadata
        );
        return StageResult.of(plan, "Composition inputs assembled from gated artifacts.");
    }
}
