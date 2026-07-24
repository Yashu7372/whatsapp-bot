package com.whatsappbot.video.image;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GeminiImageGenerationProvider implements ImageGenerationProvider {

    private final MediaGenerationProperties properties;
    private final WebClient webClient;

    public GeminiImageGenerationProvider(MediaGenerationProperties properties) {
        this.properties = properties;
        this.webClient = WebClient.builder()
                .baseUrl(properties.getGemini().getBaseUrl())
                .build();
    }

    @Override
    public String code() {
        return "GEMINI";
    }

    @Override
    public boolean available() {
        return properties.getGemini().isEnabled()
                && properties.getGemini().getApiKey() != null
                && !properties.getGemini().getApiKey().isBlank();
    }

    @Override
    public BigDecimal estimateCost(StoryboardQualityMode qualityMode) {
        return qualityMode == StoryboardQualityMode.QUALITY
                ? properties.getGemini().getProEstimatedCostUsd()
                : properties.getGemini().getFlashEstimatedCostUsd();
    }

    @Override
    public GeneratedImage generate(ImageGenerationRequest request) {
        if (!available()) {
            throw new IllegalStateException("Gemini image provider is not configured");
        }

        List<Map<String, Object>> inputs = new ArrayList<>();
        inputs.add(Map.of("type", "text", "text", request.prompt()));
        request.references().stream().limit(5).forEach(reference -> inputs.add(Map.of(
                "type", "image",
                "mime_type", reference.mimeType(),
                "data", Base64.getEncoder().encodeToString(reference.data())
        )));

        Map<String, Object> responseFormat = new LinkedHashMap<>();
        responseFormat.put("type", "image");
        responseFormat.put("mime_type", "image/jpeg");
        responseFormat.put("aspect_ratio", "9:16");
        responseFormat.put("image_size",
                request.qualityMode() == StoryboardQualityMode.QUALITY ? "2K" : "1K");

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.qualityMode() == StoryboardQualityMode.QUALITY
                ? properties.getGemini().getProModel()
                : properties.getGemini().getFlashModel());
        body.put("input", inputs);
        body.put("response_format", responseFormat);

        JsonNode response = webClient.post()
                .uri("/interactions")
                .header("x-goog-api-key", properties.getGemini().getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block(properties.getGemini().getTimeout());

        ImagePayload payload = extractImage(response);
        return new GeneratedImage(
                Base64.getDecoder().decode(payload.data()),
                payload.mimeType(),
                code(),
                estimateCost(request.qualityMode())
        );
    }

    private ImagePayload extractImage(JsonNode response) {
        if (response == null) {
            throw new IllegalStateException("Gemini returned an empty response");
        }
        JsonNode direct = response.path("output_image");
        if (direct.hasNonNull("data")) {
            return new ImagePayload(
                    direct.path("data").asText(),
                    direct.path("mime_type").asText("image/jpeg")
            );
        }
        for (JsonNode step : response.path("steps")) {
            for (JsonNode content : step.path("content")) {
                if ("image".equals(content.path("type").asText()) && content.hasNonNull("data")) {
                    return new ImagePayload(
                            content.path("data").asText(),
                            content.path("mime_type").asText("image/jpeg")
                    );
                }
            }
        }
        throw new IllegalStateException("Gemini response did not contain an output image");
    }

    private record ImagePayload(String data, String mimeType) {
    }
}
