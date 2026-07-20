package com.whatsappbot.stock;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Slf4j
@Service
public class StockMediaService {

    private final String pexelsApiKey;
    private final String pixabayApiKey;
    private final RestClient pexels = RestClient.builder().baseUrl("https://api.pexels.com").build();
    private final RestClient pixabay = RestClient.builder().baseUrl("https://pixabay.com").build();

    public StockMediaService(
            @Value("${app.media.pexels-api-key:}") String pexelsApiKey,
            @Value("${app.media.pixabay-api-key:}") String pixabayApiKey) {
        this.pexelsApiKey = pexelsApiKey;
        this.pixabayApiKey = pixabayApiKey;
    }

    public SearchResult search(String query, String provider, int page, int perPage) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(perPage, 20));
        String normalizedProvider = provider == null ? "AUTO" : provider.toUpperCase(Locale.ROOT);
        List<StockVideo> results = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (("AUTO".equals(normalizedProvider) || "PEXELS".equals(normalizedProvider)) && pexelsAvailable()) {
            try {
                results.addAll(searchPexels(query, safePage, safeSize));
            } catch (Exception e) {
                log.warn("Pexels search failed: {}", e.getMessage());
                warnings.add("Pexels search failed: " + e.getMessage());
            }
        }
        if (("AUTO".equals(normalizedProvider) || "PIXABAY".equals(normalizedProvider)) && pixabayAvailable()) {
            try {
                results.addAll(searchPixabay(query, safePage, safeSize));
            } catch (Exception e) {
                log.warn("Pixabay search failed: {}", e.getMessage());
                warnings.add("Pixabay search failed: " + e.getMessage());
            }
        }
        if (results.isEmpty() && !pexelsAvailable() && !pixabayAvailable()) {
            warnings.add("Configure PEXELS_API_KEY or PIXABAY_API_KEY to search stock videos.");
        }
        return new SearchResult(results.stream().limit(safeSize).toList(), capabilities(), warnings);
    }

    public Capabilities capabilities() {
        return new Capabilities(pexelsAvailable(), pixabayAvailable());
    }

    private List<StockVideo> searchPexels(String query, int page, int perPage) {
        JsonNode response = pexels.get()
                .uri(uri -> uri.path("/videos/search")
                        .queryParam("query", query)
                        .queryParam("orientation", "portrait")
                        .queryParam("page", page)
                        .queryParam("per_page", perPage)
                        .build())
                .header("Authorization", pexelsApiKey)
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            return List.of();
        }
        List<StockVideo> videos = new ArrayList<>();
        for (JsonNode item : response.path("videos")) {
            JsonNode selected = selectPexelsFile(item.path("video_files"));
            if (selected == null) {
                continue;
            }
            videos.add(new StockVideo(
                    "PEXELS",
                    item.path("id").asText(),
                    item.path("url").asText(),
                    selected.path("link").asText(),
                    item.path("image").asText(),
                    item.path("duration").asInt(),
                    item.path("user").path("name").asText(),
                    item.path("user").path("url").asText(),
                    selected.path("width").asInt(),
                    selected.path("height").asInt()
            ));
        }
        return videos;
    }

    private JsonNode selectPexelsFile(JsonNode files) {
        return stream(files)
                .filter(n -> n.path("link").asText().startsWith("https://"))
                .min(Comparator.comparingInt(n -> Math.abs(n.path("width").asInt(1080) - 1080)
                        + Math.abs(n.path("height").asInt(1920) - 1920)))
                .orElse(null);
    }

    private List<StockVideo> searchPixabay(String query, int page, int perPage) {
        JsonNode response = pixabay.get()
                .uri(uri -> uri.path("/api/videos/")
                        .queryParam("key", pixabayApiKey)
                        .queryParam("q", query)
                        .queryParam("page", page)
                        .queryParam("per_page", perPage)
                        .queryParam("safesearch", true)
                        .queryParam("order", "popular")
                        .build())
                .retrieve()
                .body(JsonNode.class);
        if (response == null) {
            return List.of();
        }
        List<StockVideo> videos = new ArrayList<>();
        for (JsonNode item : response.path("hits")) {
            JsonNode selected = item.path("videos").path("medium");
            if (selected.isMissingNode() || selected.path("url").asText().isBlank()) {
                selected = item.path("videos").path("small");
            }
            if (selected.isMissingNode() || selected.path("url").asText().isBlank()) {
                continue;
            }
            videos.add(new StockVideo(
                    "PIXABAY",
                    item.path("id").asText(),
                    item.path("pageURL").asText(),
                    selected.path("url").asText(),
                    selected.path("thumbnail").asText(),
                    item.path("duration").asInt(),
                    item.path("user").asText(),
                    "https://pixabay.com/users/" + item.path("user").asText(),
                    selected.path("width").asInt(),
                    selected.path("height").asInt()
            ));
        }
        return videos;
    }

    private java.util.stream.Stream<JsonNode> stream(JsonNode array) {
        List<JsonNode> nodes = new ArrayList<>();
        array.forEach(nodes::add);
        return nodes.stream();
    }

    private boolean pexelsAvailable() {
        return pexelsApiKey != null && !pexelsApiKey.isBlank();
    }

    private boolean pixabayAvailable() {
        return pixabayApiKey != null && !pixabayApiKey.isBlank();
    }

    public record StockVideo(
            String provider,
            String providerId,
            String sourcePageUrl,
            String downloadUrl,
            String previewUrl,
            int durationSeconds,
            String creatorName,
            String creatorUrl,
            int width,
            int height
    ) {}

    public record Capabilities(boolean pexels, boolean pixabay) {}

    public record SearchResult(List<StockVideo> items, Capabilities capabilities, List<String> warnings) {}
}
