package com.whatsappbot.trend;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@Slf4j
@Component
@Order(10)
public class YouTubeTrendProvider implements TrendProvider {

    private final String apiKey;
    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://www.googleapis.com/youtube/v3")
            .build();

    public YouTubeTrendProvider(@Value("${app.trends.youtube-api-key:}") String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public String code() {
        return "YOUTUBE_MOST_POPULAR";
    }

    @Override
    public String displayName() {
        return "YouTube Most Popular";
    }

    @Override
    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public boolean supports(String platformCode) {
        return "YOUTUBE".equalsIgnoreCase(platformCode) || "YOUTUBE_SHORTS".equalsIgnoreCase(platformCode);
    }

    @Override
    public List<ObservedTrend> discover(TrendQuery query) {
        if (!available()) {
            return List.of();
        }
        String region = normalizeCountry(query.country());
        try {
            JsonNode response = restClient.get()
                    .uri(uri -> uri.path("/videos")
                            .queryParam("part", "snippet,statistics")
                            .queryParam("chart", "mostPopular")
                            .queryParam("regionCode", region)
                            .queryParam("maxResults", Math.min(query.count(), 20))
                            .queryParam("key", apiKey)
                            .build())
                    .retrieve()
                    .body(JsonNode.class);
            if (response == null) {
                return List.of();
            }
            List<ObservedTrend> results = new ArrayList<>();
            int rank = 0;
            for (JsonNode item : response.path("items")) {
                String title = item.path("snippet").path("title").asText();
                String channel = item.path("snippet").path("channelTitle").asText();
                long views = item.path("statistics").path("viewCount").asLong(0);
                double score = Math.max(0.6, 1.0 - (rank++ * 0.04));
                results.add(new ObservedTrend(
                        title,
                        "#" + title.replaceAll("[^A-Za-z0-9]", ""),
                        "Popular YouTube video from %s with %,d views. Use the underlying topic, not copyrighted footage, for an original %s reel."
                                .formatted(channel, views, query.industry()),
                        score,
                        displayName()
                ));
            }
            return results;
        } catch (Exception e) {
            log.warn("YouTube trend discovery failed. region={} error={}", region, e.getMessage());
            return List.of();
        }
    }

    private String normalizeCountry(String country) {
        if (country == null || country.isBlank() || "GLOBAL".equalsIgnoreCase(country)) {
            return "US";
        }
        String normalized = country.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "UAE", "UNITED ARAB EMIRATES", "DUBAI" -> "AE";
            case "INDIA" -> "IN";
            case "UNITED KINGDOM", "UK" -> "GB";
            case "UNITED STATES", "USA" -> "US";
            default -> normalized.length() == 2 ? normalized : "US";
        };
    }
}
