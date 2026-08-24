package com.whatsappbot.video.engine.adapter;

import com.whatsappbot.video.engine.GenerationAdapter;
import com.whatsappbot.video.engine.GenerationArtifact;
import com.whatsappbot.video.engine.GenerationArtifactType;
import com.whatsappbot.video.engine.GenerationCapability;
import com.whatsappbot.video.engine.GenerationContext;
import com.whatsappbot.video.engine.StageResult;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Uses the shot list already produced by the content stage as the first visual
 * plan. A richer AI visual planner can replace this adapter later.
 */
@Component
public class ScriptVisualPlanAdapter implements GenerationAdapter {

    @Override
    public GenerationCapability capability() {
        return GenerationCapability.VISUAL_PLAN;
    }

    @Override
    public String name() {
        return "script-visual-plan";
    }

    @Override
    public StageResult generate(GenerationContext context) {
        GenerationArtifact script = context.artifact(GenerationArtifactType.SCRIPT)
                .orElseThrow(() -> new IllegalStateException("Script artifact is required before visual planning."));
        String shotList = script.metadata().get("shotList");
        if (shotList == null || shotList.isBlank() || "[]".equals(shotList.trim())) {
            throw new IllegalStateException("Script did not contain a usable shot list.");
        }

        GenerationArtifact plan = new GenerationArtifact(
                GenerationArtifactType.VISUAL_PLAN,
                shotList,
                "script-visual-plan",
                Map.of("source", "script-shot-list")
        );
        return StageResult.of(plan, "Structured script shots promoted to the visual plan.");
    }
}
