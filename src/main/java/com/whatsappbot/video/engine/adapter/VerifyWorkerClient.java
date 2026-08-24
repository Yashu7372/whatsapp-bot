package com.whatsappbot.video.engine.adapter;

import com.whatsappbot.video.RendererProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.UUID;

@Component
@EnableConfigurationProperties(RendererProperties.class)
public class VerifyWorkerClient {

    private final RendererProperties properties;
    private final RestClient restClient;

    public VerifyWorkerClient(RendererProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().baseUrl(properties.getBaseUrl()).build();
    }

    public VerifyResponse verify(UUID generationId, UUID tenantId, String videoPath) {
        VerifyResponse response = restClient.post()
                .uri("/v1/verify")
                .body(Map.of(
                        "jobId", generationId.toString(),
                        "tenantId", tenantId.toString(),
                        "videoPath", videoPath
                ))
                .retrieve()
                .body(VerifyResponse.class);
        if (response == null) {
            throw new IllegalStateException("QA worker returned no response.");
        }
        return response;
    }

    public boolean enabled() {
        return properties.isEnabled();
    }

    public record VerifyResponse(
            boolean passed,
            double durationSeconds,
            int width,
            int height,
            long sizeBytes,
            String message
    ) {}
}
