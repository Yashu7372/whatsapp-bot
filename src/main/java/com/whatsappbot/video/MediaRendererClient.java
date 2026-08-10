package com.whatsappbot.video;

import com.whatsappbot.video.dialogue.DialogueScript;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@EnableConfigurationProperties(RendererProperties.class)
public class MediaRendererClient {

    private final RendererProperties properties;
    private final RestClient restClient;

    public MediaRendererClient(RendererProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().baseUrl(properties.getBaseUrl()).build();
    }

    public RenderResult render(RenderJobService.RenderContext context) {
        Map<String, Object> request = Map.ofEntries(
                Map.entry("jobId", context.jobId().toString()),
                Map.entry("tenantId", context.tenantId().toString()),
                Map.entry("templateCode", context.templateCode()),
                Map.entry("title", nullToEmpty(context.title())),
                Map.entry("hook", nullToEmpty(context.hook())),
                Map.entry("voiceoverText", nullToEmpty(context.scriptBody())),
                Map.entry("shotList", context.shotList()),
                Map.entry("assetPaths", context.assetPaths()),
                Map.entry("assetUrls", context.assetUrls()),
                Map.entry("voice", nullToEmpty(context.voice())),
                Map.entry("brandName", nullToEmpty(context.brandName())),
                Map.entry("callToAction", nullToEmpty(context.callToAction())),
                Map.entry("durationSeconds", context.durationSeconds()),
                Map.entry("outputPath", context.outputPath())
        );

        RendererResponse response = restClient.post()
                .uri("/v1/render")
                .body(request)
                .retrieve()
                .body(RendererResponse.class);
        if (response == null || response.outputPath() == null || response.outputPath().isBlank()) {
            throw new IllegalStateException("Renderer returned no output path");
        }
        log.info("Renderer completed job={} output={}", context.jobId(), response.outputPath());
        return new RenderResult(response.outputPath(), response.durationSeconds(), response.warnings());
    }

    public void submitDialogueRender(
            String jobId, UUID tenantId, DialogueScript script, String outputPath
    ) {
        List<Map<String, Object>> turns = script.turns().stream()
                .map(turn -> Map.<String, Object>of(
                        "speaker", turn.speaker(),
                        "emotion", turn.emotion(),
                        "text", turn.text(),
                        "duration_seconds", turn.durationSeconds()
                ))
                .toList();

        Map<String, Object> request = Map.of(
                "jobId", jobId,
                "tenantId", tenantId.toString(),
                "turns", turns,
                "outputPath", outputPath
        );

        DialogueSubmitResponse response = restClient.post()
                .uri("/v2/dialogue/render")
                .body(request)
                .retrieve()
                .body(DialogueSubmitResponse.class);

        log.info("Dialogue render submitted. job={} status={} mode={}",
                jobId,
                response != null ? response.status() : "null",
                response != null ? response.renderMode() : "unknown");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getDialogueRenderStatus(String jobId) {
        return restClient.get()
                .uri("/v2/dialogue/render/{jobId}", jobId)
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getDialogueCapabilities() {
        return restClient.get()
                .uri("/v2/dialogue/capabilities")
                .retrieve()
                .body(Map.class);
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getCharacterPackStatus() {
        return restClient.get()
                .uri("/v1/character-pack/status")
                .retrieve()
                .body(Map.class);
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record RendererResponse(String status, String outputPath, double durationSeconds,
                                   List<String> warnings) {}

    public record RenderResult(String outputPath, double durationSeconds, List<String> warnings) {}

    public record DialogueSubmitResponse(String jobId, String status, String renderMode) {}
}
