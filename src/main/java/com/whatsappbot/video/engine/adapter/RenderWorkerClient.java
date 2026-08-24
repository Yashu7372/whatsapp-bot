package com.whatsappbot.video.engine.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.video.RendererProperties;
import com.whatsappbot.video.engine.GenerationArtifact;
import com.whatsappbot.video.engine.GenerationArtifactType;
import com.whatsappbot.video.engine.GenerationContext;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@EnableConfigurationProperties(RendererProperties.class)
public class RenderWorkerClient {

    private final RendererProperties properties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public RenderWorkerClient(RendererProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().baseUrl(properties.getBaseUrl()).build();
    }

    public RenderResponse render(GenerationContext context) {
        GenerationArtifact script = context.artifact(GenerationArtifactType.SCRIPT)
                .orElseThrow(() -> new IllegalStateException("Script artifact is required before rendering."));
        GenerationArtifact audio = context.artifact(GenerationArtifactType.NARRATION_AUDIO)
                .orElseThrow(() -> new IllegalStateException("Narration audio is required before rendering."));
        GenerationArtifact visualPlan = context.artifact(GenerationArtifactType.VISUAL_PLAN)
                .orElseThrow(() -> new IllegalStateException("Visual plan is required before rendering."));

        List<String> assetPaths = new ArrayList<>();
        List<String> assetUrls = new ArrayList<>();
        for (GenerationArtifact artifact : context.artifacts()) {
            if (artifact.type() != GenerationArtifactType.BROLL) {
                continue;
            }
            if (artifact.value().startsWith("https://")) {
                assetUrls.add(artifact.value());
            } else {
                assetPaths.add(artifact.value());
            }
        }

        Path output = Path.of(properties.getOutputDir())
                .toAbsolutePath()
                .normalize()
                .resolve(context.tenantId().toString())
                .resolve(context.generationId().toString())
                .resolve("final.mp4")
                .normalize();

        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jobId", context.generationId().toString());
        request.put("tenantId", context.tenantId().toString());
        request.put("templateCode", context.option("templateCode", "PRODUCT_HOOK_V1"));
        request.put("title", script.metadata().getOrDefault("title", context.topic()));
        request.put("hook", script.metadata().getOrDefault("hook", ""));
        request.put("voiceoverText", "");
        request.put("shotList", parsePlan(visualPlan.value()));
        request.put("assetPaths", assetPaths.stream().limit(8).toList());
        request.put("assetUrls", assetUrls.stream().limit(8).toList());
        request.put("voice", context.option("voice", "af_heart"));
        request.put("brandName", context.option("brandName", ""));
        request.put("callToAction", context.option("callToAction", ""));
        request.put("narrationAudioPath", audio.value());
        request.put("durationSeconds", measuredDurationSeconds(audio));
        request.put("outputPath", output.toString());

        RenderResponse response = restClient.post()
                .uri("/v1/render")
                .body(request)
                .retrieve()
                .body(RenderResponse.class);
        if (response == null || response.outputPath() == null || response.outputPath().isBlank()) {
            throw new IllegalStateException("Render worker returned no final video path.");
        }
        return response;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    private Object parsePlan(String value) {
        try {
            return objectMapper.readValue(value, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException("Visual plan is not valid JSON.", e);
        }
    }

    private int measuredDurationSeconds(GenerationArtifact audio) {
        try {
            double measured = Double.parseDouble(audio.metadata().getOrDefault("durationSeconds", "0"));
            return Math.max(5, Math.min((int) Math.ceil(measured), 90));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("Narration duration is invalid.", e);
        }
    }

    public record RenderResponse(
            String status,
            String outputPath,
            double durationSeconds,
            List<String> warnings
    ) {}
}
