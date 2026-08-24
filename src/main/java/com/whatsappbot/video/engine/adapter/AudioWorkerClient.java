package com.whatsappbot.video.engine.adapter;

import com.whatsappbot.video.RendererProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

@Component
@EnableConfigurationProperties(RendererProperties.class)
public class AudioWorkerClient {

    private final RendererProperties properties;
    private final RestClient restClient;

    public AudioWorkerClient(RendererProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().baseUrl(properties.getBaseUrl()).build();
    }

    public AudioResponse generate(UUID generationId, UUID tenantId, String text,
                                  String voice, int targetDurationSeconds, String outputPath) {
        AudioResponse response = restClient.post()
                .uri("/v1/audio/generate")
                .body(Map.of(
                        "jobId", generationId.toString(),
                        "tenantId", tenantId.toString(),
                        "text", text,
                        "voice", voice,
                        "targetDurationSeconds", targetDurationSeconds,
                        "outputPath", outputPath
                ))
                .retrieve()
                .body(AudioResponse.class);
        if (response == null || response.outputPath() == null || response.outputPath().isBlank()) {
            throw new IllegalStateException("Audio worker returned no output path.");
        }
        return response;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public record AudioResponse(
            String status,
            String outputPath,
            double durationSeconds,
            String provider
    ) {}
}
