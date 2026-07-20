package com.whatsappbot.trend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.trend.provider.TrendCandidate;
import com.whatsappbot.trend.provider.TrendProvider;
import com.whatsappbot.trend.provider.TrendQuery;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrendDiscoveryService {

    private final TrendSignalRepository trendSignalRepository;
    private final TrendSourceRepository trendSourceRepository;
    private final TrendScoringService trendScoringService;
    private final List<TrendProvider> trendProviders;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper;

    public List<TrendSignalEntity> discover(TenantEntity tenant, String industry, String country,
                                             String platformCode, int count) {
        int safeCount = Math.max(1, Math.min(count, 10));
        TrendQuery query = new TrendQuery(
                defaultText(industry, "General"),
                defaultText(country, "AE"),
                defaultText(platformCode, "INSTAGRAM"),
                safeCount
        );

        Map<String, TrendCandidate> unique = new LinkedHashMap<>();
        for (TrendProvider provider : trendProviders) {
            if (!provider.available()) {
                continue;
            }
            try {
                for (TrendCandidate candidate : provider.discover(query)) {
                    if (candidate.keyword() == null || candidate.keyword().isBlank()) {
                        continue;
                    }
                    unique.putIfAbsent(candidate.keyword().trim().toLowerCase(Locale.ROOT), candidate);
                    if (unique.size() >= safeCount) {
                        break;
                    }
                }
            } catch (Exception error) {
                log.warn("Trend provider failed. provider={} error={}", provider.code(), error.getMessage());
            }
            if (unique.size() >= safeCount) {
                break;
            }
        }

        if (unique.isEmpty()) {
            for (TrendCandidate candidate : discoverWithAiFallback(query)) {
                unique.putIfAbsent(candidate.keyword().trim().toLowerCase(Locale.ROOT), candidate);
            }
        }

        List<TrendSignalEntity> results = unique.values().stream()
                .limit(safeCount)
                .map(candidate -> persist(tenant, query, candidate))
                .toList();
        log.info("Discovered {} trends for tenant={} platform={} using live providers/fallback",
                results.size(), tenant.getId(), query.platformCode());
        return results;
    }

    public String recommend(String keyword, String topic, String platformCode) {
        String prompt = """
                You are a content strategist. Given this observed social media/search trend, suggest 3 specific content ideas a business could create.

                Trend keyword: %s
                Trend description: %s
                Target platform: %s

                Return a JSON object with:
                {
                  "summary": "1 sentence why this trend matters now",
                  "ideas": [
                    {"type": "REEL/POST/STORY", "title": "content title", "angle": "specific angle/hook to use"}
                  ],
                  "bestTime": "best time to post this content",
                  "warning": "brand safety concern or null"
                }
                """.formatted(keyword, topic, platformCode);
        try {
            return stripMarkdown(chatModel.chat(prompt));
        } catch (Exception error) {
            log.warn("AI trend recommendation failed: {}", error.getMessage());
            return "{\"summary\":\"This topic is currently receiving attention.\",\"ideas\":[{\"type\":\"REEL\",\"title\":\"Our take on "
                    + escapeJson(keyword) + "\",\"angle\":\"Connect the trend to a concrete customer problem\"}],\"bestTime\":\"Test during your audience's peak engagement window\",\"warning\":null}";
        }
    }

    private TrendSignalEntity persist(TenantEntity tenant, TrendQuery query, TrendCandidate candidate) {
        TrendSourceEntity source = sourceFor(tenant, candidate.sourceName(), candidate.sourceType());
        TrendSignalEntity signal = TrendSignalEntity.create(
                tenant,
                source.getId(),
                candidate.keyword(),
                candidate.hashtag(),
                candidate.topic(),
                query.country(),
                query.industry(),
                query.platformCode(),
                Math.max(0.0, Math.min(candidate.rawScore(), 1.0))
        );
        trendScoringService.applyScores(signal);
        return trendSignalRepository.save(signal);
    }

    private TrendSourceEntity sourceFor(TenantEntity tenant, String name, TrendSourceType type) {
        return trendSourceRepository.findAllByTenantIdAndActive(tenant.getId(), true).stream()
                .filter(source -> source.getName().equalsIgnoreCase(name))
                .findFirst()
                .orElseGet(() -> trendSourceRepository.save(TrendSourceEntity.create(tenant, name, type)));
    }

    private List<TrendCandidate> discoverWithAiFallback(TrendQuery query) {
        String prompt = """
                You are a content ideation assistant operating without live internet access.
                Produce %d plausible content opportunities for the supplied business context.
                Do not claim these are verified live trends. They will be stored as AI fallback ideas.

                Industry: %s
                Country/Region: %s
                Target platform: %s
                Current date: %s

                Return ONLY a JSON array:
                [
                  {
                    "keyword": "topic or phrase",
                    "hashtag": "#relevantHashtag",
                    "topic": "why this is a useful content opportunity",
                    "rawScore": 0.4-0.7
                  }
                ]
                """.formatted(query.count(), query.industry(), query.country(), query.platformCode(), LocalDate.now());

        List<TrendCandidate> result = new ArrayList<>();
        try {
            JsonNode array = objectMapper.readTree(stripMarkdown(chatModel.chat(prompt)));
            if (!array.isArray()) {
                return List.of();
            }
            for (JsonNode node : array) {
                String keyword = node.path("keyword").asText();
                if (keyword.isBlank()) {
                    continue;
                }
                result.add(new TrendCandidate(
                        keyword,
                        node.path("hashtag").asText("#contentidea"),
                        "AI fallback idea (not a verified live trend): " + node.path("topic").asText(),
                        Math.max(0.4, Math.min(node.path("rawScore").asDouble(0.55), 0.7)),
                        "Gemma Offline Fallback",
                        TrendSourceType.API
                ));
            }
        } catch (Exception error) {
            log.warn("Offline trend fallback failed: {}", error.getMessage());
        }
        return result;
    }

    private String stripMarkdown(String text) {
        if (text == null) {
            return "";
        }
        String value = text.trim();
        if (value.startsWith("```")) {
            value = value.replaceAll("(?s)^```\\w*\\n?", "").replaceAll("```$", "").trim();
        }
        int arrayStart = value.indexOf('[');
        int arrayEnd = value.lastIndexOf(']');
        int objectStart = value.indexOf('{');
        int objectEnd = value.lastIndexOf('}');
        if (arrayStart >= 0 && arrayEnd > arrayStart && (objectStart < 0 || arrayStart < objectStart)) {
            return value.substring(arrayStart, arrayEnd + 1);
        }
        if (objectStart >= 0 && objectEnd > objectStart) {
            return value.substring(objectStart, objectEnd + 1);
        }
        return value;
    }

    private String defaultText(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
