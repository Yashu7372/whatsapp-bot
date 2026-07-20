package com.whatsappbot.trend.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.whatsappbot.trend.TrendSourceType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Slf4j
@Component
public class YouTubeTrendProvider implements TrendProvider {

    private static final Map<String, String> COUNTRY_CODES = Map.ofEntries(
            Map.entry("UAE", "AE"),
            Map.entry("UNITED ARAB EMIRATES", "AE"),
            Map.entry("DUBAI", "AE"),
            Map.entry("INDIA", "IN"),
            Map.entry("UNITED STATES", "US"),
            Map.entry("USA", "US"),
            Map.entry("UNITED KINGDOM", "GB"),
            Map.entry("UK", "GB"),
            Map.entry("SAUDI ARABIA", "SA")
    );

    private final WebClient webClient;
    private final TrendProviderProperties properties;

    public YouTubeTrendProvider(WebClient.Builder builder, TrendProviderProperties properties) {
        this.webClient = builder.build();
        this.properties = properties;
    }

    @Override
    public String code() {
        return "YOUTUBE_MOST_POPULAR";
    }

    @Override
    public boolean available() {
        return properties.getYoutubeApiKey() != null && !properties.getYoutubeApiKey().isBlank();
    }

    @Override
    public List<TrendCandidate> discover(TrendQuery query) {
        if (!available()) {
            return List.of();
        }
        try {
            JsonNode response = webClient.get()
                    .uri(builder -> builder
                            .scheme("https")
                            .host("www.googleapis.com")
                            .path("/youtube/v3/videos")
                            .queryParam("part", "snippet,statistics")
                            .queryParam("chart", "mostPopular")
                            .queryParam("regionCode", countryCode(query.country()))
                            .queryParam("maxResults", Math.min(query.count(), 20))
                            .queryParam("key", properties.getYoutubeApiKey())
                            .build())
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(20));

            if (response == null || !response.path("items").isArray()) {
                return List.of();
            }

            List<TrendCandidate> result = new ArrayList<>();
            int index = 0;
            for (JsonNode item : response.path("items")) {
                JsonNode snippet = item.path("snippet");
                String title = snippet.path("title").asText();
                if (title.isBlank()) {
                    continue;
                }
                String channel = snippet.path("channelTitle").asText("YouTube creator");
                long views = item.path("statistics").path("viewCount").asLong(0);
                String hashtag = firstTag(snippet.path("tags"), title);
                double rankScore = Math.max(0.58, 0.98 - index * 0.035);
                double viewBoost = views > 0 ? Math.min(0.08, Math.log10(views + 1) / 100.0) : 0.0;
                result.add(new TrendCandidate(
                        title,
                        hashtag,
                        "Popular YouTube video from " + channel +
                                (views > 0 ? " with " + views + " views." : "."),
                        Math.min(1.0, rankScore + viewBoost),
                        "YouTube Most Popular",
                        TrendSourceType.API
                ));
                index++;
                if (index >= query.count()) {
                    break;
                }
            }
            return result;
        } catch (Exception error) {
            log.warn("YouTube trend discovery failed: {}", error.getMessage());
            return List.of();
        }
    }

    private String firstTag(JsonNode tags, String fallback) {
        if (tags.isArray() && !tags.isEmpty()) {
            return "#" + tags.get(0).asText().replaceAll("[^A-Za-z0-9]", "");
        }
        return "#" + fallback.replaceAll("[^A-Za-z0-9]", "");
    }

    private String countryCode(String country) {
        if (country == null || country.isBlank() || country.equalsIgnoreCase("global")) {
            return "AE";
        }
        String normalized = country.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() == 2) {
            return normalized;
        }
        return COUNTRY_CODES.getOrDefault(normalized, "AE");
    }
}
