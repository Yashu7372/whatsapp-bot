package com.whatsappbot.video.engine.adapter;

import com.whatsappbot.video.engine.GenerationAdapter;
import com.whatsappbot.video.engine.GenerationArtifact;
import com.whatsappbot.video.engine.GenerationArtifactType;
import com.whatsappbot.video.engine.GenerationCapability;
import com.whatsappbot.video.engine.GenerationContext;
import com.whatsappbot.video.engine.GenerationMode;
import com.whatsappbot.video.engine.StageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class RenderGenerationAdapter implements GenerationAdapter {

    private final RenderWorkerClient renderWorkerClient;

    @Override
    public GenerationCapability capability() {
        return GenerationCapability.RENDER;
    }

    @Override
    public String name() {
        return "ffmpeg-render-worker";
    }

    @Override
    public boolean supports(GenerationContext context) {
        return renderWorkerClient.enabled() && context.mode() == GenerationMode.FACELESS;
    }

    @Override
    public StageResult generate(GenerationContext context) {
        RenderWorkerClient.RenderResponse response = renderWorkerClient.render(context);
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("durationSeconds", Double.toString(response.durationSeconds()));
        if (response.warnings() != null && !response.warnings().isEmpty()) {
            metadata.put("warnings", String.join(" | ", response.warnings()));
        }

        GenerationArtifact video = new GenerationArtifact(
                GenerationArtifactType.FINAL_VIDEO,
                response.outputPath(),
                "ffmpeg-render-worker",
                metadata
        );
        return StageResult.of(video, "Final video rendered using the locked narration timeline.");
    }
}
