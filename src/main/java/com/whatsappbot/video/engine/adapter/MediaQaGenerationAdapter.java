package com.whatsappbot.video.engine.adapter;

import com.whatsappbot.video.engine.GenerationAdapter;
import com.whatsappbot.video.engine.GenerationArtifact;
import com.whatsappbot.video.engine.GenerationArtifactType;
import com.whatsappbot.video.engine.GenerationCapability;
import com.whatsappbot.video.engine.GenerationContext;
import com.whatsappbot.video.engine.StageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class MediaQaGenerationAdapter implements GenerationAdapter {

    private final VerifyWorkerClient verifyWorkerClient;

    @Override
    public GenerationCapability capability() {
        return GenerationCapability.VERIFY;
    }

    @Override
    public String name() {
        return "media-technical-qa";
    }

    @Override
    public boolean supports(GenerationContext context) {
        return verifyWorkerClient.enabled();
    }

    @Override
    public StageResult generate(GenerationContext context) {
        GenerationArtifact video = context.artifact(GenerationArtifactType.FINAL_VIDEO)
                .orElseThrow(() -> new IllegalStateException("Final video is required before QA."));
        VerifyWorkerClient.VerifyResponse response = verifyWorkerClient.verify(
                context.generationId(), context.tenantId(), video.value());

        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("passed", Boolean.toString(response.passed()));
        metadata.put("durationSeconds", Double.toString(response.durationSeconds()));
        metadata.put("width", Integer.toString(response.width()));
        metadata.put("height", Integer.toString(response.height()));
        metadata.put("sizeBytes", Long.toString(response.sizeBytes()));

        GenerationArtifact report = new GenerationArtifact(
                GenerationArtifactType.QA_REPORT,
                response.message(),
                "media-technical-qa",
                metadata
        );
        return StageResult.of(report, response.message());
    }
}
