package com.whatsappbot.reels;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;

@Component
public class ReelRendererClient {

    private static final MediaType VIDEO_MP4 = MediaType.parseMediaType("video/mp4");

    private final WebClient webClient;
    private final ReelProperties properties;

    public ReelRendererClient(WebClient.Builder builder, ReelProperties properties) {
        this.properties = properties;
        this.webClient = builder.baseUrl(properties.getRendererBaseUrl()).build();
    }

    public byte[] render(RenderPayload payload) {
        byte[] result = webClient.post()
                .uri("/render")
                .contentType(MediaType.APPLICATION_JSON)
                .accept(VIDEO_MP4)
                .bodyValue(payload)
                .retrieve()
                .bodyToMono(byte[].class)
                .block(Duration.ofSeconds(properties.getRenderTimeoutSeconds()));

        if (result == null || result.length == 0) {
            throw new IllegalStateException("Renderer returned an empty MP4 payload");
        }
        return result;
    }

    public record RenderPayload(
            String jobId,
            String templateCode,
            String title,
            String hook,
            String script,
            String caption,
            int durationSeconds,
            JsonNode shots,
            boolean includeVoice,
            String voice,
            List<RenderAsset> assets
    ) {}

    public record RenderAsset(
            String name,
            String contentType,
            String dataBase64,
            String url
    ) {}
}
