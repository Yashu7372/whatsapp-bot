package com.whatsappbot.stockmedia;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class StockMediaService {

    private final WebClient webClient;
    private final StockMediaProperties properties;

    public StockMediaService(WebClient.Builder builder, StockMediaProperties properties) {
        this.webClient = builder.build();
        this.properties = properties;
    }

    public SearchResult search(String query, String provider, int limit) {
        String normalizedQuery = query == null || query.isBlank() ? "business" : query.trim();
        int safeLimit = Math.max(1, Math.min(limit, 20));
        String selected = provider == null ? "AUTO" : provider.trim().toUpperCase(Locale.ROOT);
        List<StockMediaItem> items = new ArrayList<>();

        if ((selected.equals("AUTO") || selected.equals("PEXELS")) && configured(properties.getPexelsApiKey())) {
            items.addAll(searchPexels(normalizedQuery, safeLimit));
        }
        if ((selected.equals("AUTO") || selected.equals("PIXABAY")) && configured(properties.getPixabayApiKey())) {
            items.addAll(searchPixabay(normalizedQuery, safeLimit));
        }

        return new SearchResult(
                items.stream().limit(safeLimit).toList(),
                configured(properties.getPexelsApiKey()),
                configured(properties.getPixabayApiKey())
        );
    }

    private List<StockMediaItem> searchPexels(String query, int limit) {
        try {
            JsonNode response = webClient.get()
                    .uri(builder -> builder
                            .scheme("https")
                            .host("api.pexels.com")
                            .path("/videos/search")
                            .queryParam("query", query)
                            .queryParam("orientation", "portrait")
                            .queryParam("per_page", limit)
                            .build())
                    .header("Authorization", properties.getPexelsApiKey())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(20));

            if (response == null || !response.path("videos").isArray()) {
                return List.of();
            }

            List<StockMediaItem> result = new ArrayList<>();
            for (JsonNode video : response.path("videos")) {
                JsonNode file = choosePexelsFile(video.path("video_files"));
                if (file == null || file.path("link").asText().isBlank()) {
                    continue;
                }
                result.add(new StockMediaItem(
                        "PEXELS",
                        video.path("id").asText(),
                        "VIDEO",
                        video.path("image").asText(null),
                        file.path("link").asText(),
                        file.path("width").asInt(video.path("width").asInt()),
                        file.path("height").asInt(video.path("height").asInt()),
                        video.path("user").path("name").asText("Pexels creator"),
                        video.path("url").asText(null)
                ));
            }
            return result;
        } catch (Exception error) {
            log.warn("Pexels media search failed: {}", error.getMessage());
            return List.of();
        }
    }

    private JsonNode choosePexelsFile(JsonNode files) {
        if (!files.isArray()) {
            return null;
        }
        List<JsonNode> candidates = new ArrayList<>();
        files.forEach(candidates::add);
        return candidates.stream()
                .filter(file -> file.path("height").asInt() >= file.path("width").asInt())
                .filter(file -> file.path("file_type").asText("").contains("mp4"))
                .min(Comparator.comparingInt(file -> Math.abs(file.path("height").asInt() - 1920)))
                .orElseGet(() -> candidates.stream()
                        .filter(file -> file.path("file_type").asText("").contains("mp4"))
                        .findFirst()
                        .orElse(null));
    }

    private List<StockMediaItem> searchPixabay(String query, int limit) {
        try {
            JsonNode response = webClient.get()
                    .uri(builder -> builder
                            .scheme("https")
                            .host("pixabay.com")
                            .path("/api/videos/")
                            .queryParam("key", properties.getPixabayApiKey())
                            .queryParam("q", query)
                            .queryParam("safesearch", true)
                            .queryParam("video_type", "film")
                            .queryParam("per_page", limit)
                            .build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(20));

            if (response == null || !response.path("hits").isArray()) {
                return List.of();
            }

            List<StockMediaItem> result = new ArrayList<>();
            for (JsonNode hit : response.path("hits")) {
                JsonNode video = firstAvailable(
                        hit.path("videos").path("medium"),
                        hit.path("videos").path("small"),
                        hit.path("videos").path("large"),
                        hit.path("videos").path("tiny")
                );
                if (video == null || video.path("url").asText().isBlank()) {
                    continue;
                }
                String videoUrl = video.path("url").asText();
                result.add(new StockMediaItem(
                        "PIXABAY",
                        hit.path("id").asText(),
                        "VIDEO",
                        videoUrl,
                        videoUrl,
                        video.path("width").asInt(),
                        video.path("height").asInt(),
                        hit.path("user").asText("Pixabay creator"),
                        hit.path("pageURL").asText(null)
                ));
            }
            return result;
        } catch (Exception error) {
            log.warn("Pixabay media search failed: {}", error.getMessage());
            return List.of();
        }
    }

    private JsonNode firstAvailable(JsonNode... values) {
        for (JsonNode value : values) {
            if (value != null && value.isObject() && !value.path("url").asText().isBlank()) {
                return value;
            }
        }
        return null;
    }

    private boolean configured(String value) {
        return value != null && !value.isBlank();
    }

    public record StockMediaItem(
            String provider,
            String providerId,
            String mediaType,
            String previewUrl,
            String downloadUrl,
            int width,
            int height,
            String creator,
            String sourceUrl
    ) {}

    public record SearchResult(
            List<StockMediaItem> items,
            boolean pexelsConfigured,
            boolean pixabayConfigured
    ) {}
}
