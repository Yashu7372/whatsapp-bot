package com.whatsappbot.video.engine.adapter;

import com.whatsappbot.video.RendererProperties;
import com.whatsappbot.video.engine.GenerationAdapter;
import com.whatsappbot.video.engine.GenerationArtifact;
import com.whatsappbot.video.engine.GenerationArtifactType;
import com.whatsappbot.video.engine.GenerationCapability;
import com.whatsappbot.video.engine.GenerationContext;
import com.whatsappbot.video.engine.StageResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class KokoroAudioGenerationAdapter implements GenerationAdapter {

    private final AudioWorkerClient audioWorkerClient;
    private final RendererProperties rendererProperties;

    @Override
    public GenerationCapability capability() {
        return GenerationCapability.AUDIO;
    }

    @Override
    public String name() {
        return "kokoro-audio";
    }

    @Override
    public boolean supports(GenerationContext context) {
        return audioWorkerClient.enabled();
    }

    @Override
    public StageResult generate(GenerationContext context) {
        GenerationArtifact script = context.artifact(GenerationArtifactType.SCRIPT)
                .orElseThrow(() -> new IllegalStateException("Script artifact is required before audio generation."));

        Path output = Path.of(rendererProperties.getOutputDir())
                .toAbsolutePath()
                .normalize()
                .resolve(context.tenantId().toString())
                .resolve(context.generationId().toString())
                .resolve("narration.wav")
                .normalize();

        AudioWorkerClient.AudioResponse response = audioWorkerClient.generate(
                context.generationId(),
                context.tenantId(),
                script.value(),
                context.option("voice", "af_heart"),
                context.targetDurationSeconds(),
                output.toString()
        );

        GenerationArtifact audio = new GenerationArtifact(
                GenerationArtifactType.NARRATION_AUDIO,
                response.outputPath(),
                response.provider(),
                Map.of("durationSeconds", Double.toString(response.durationSeconds()))
        );
        return StageResult.of(audio, "Narration generated; measured audio duration is the master timeline.");
    }
}
