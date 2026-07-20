package com.whatsappbot.video;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

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

    public boolean enabled() {
        return properties.isEnabled();
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    public record RendererResponse(String status, String outputPath, double durationSeconds,
                                   List<String> warnings) {}

    public record RenderResult(String outputPath, double durationSeconds, List<String> warnings) {}
}
