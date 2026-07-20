package com.whatsappbot.trend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.domain.tenant.TenantEntity;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

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

    @Transactional
    public List<TrendSignalEntity> discover(TenantEntity tenant, String industry, String country,
                                             String platformCode, int count) {
        TrendProvider.TrendQuery query = new TrendProvider.TrendQuery(
                industry, country, platformCode, Math.max(1, Math.min(count, 20)));

        for (TrendProvider provider : trendProviders) {
            if (!provider.available() || !provider.supports(platformCode)) {
                continue;
            }
            List<ObservedTrend> observed = provider.discover(query);
            if (!observed.isEmpty()) {
                return persistObserved(tenant, query, observed, provider.displayName());
            }
        }

        log.warn("No live trend provider returned results. Falling back to AI estimates. platform={} country={}",
                platformCode, country);
        return persistObserved(tenant, query, estimateWithAi(query), "AI Estimate");
    }

    @Transactional(readOnly = true)
    public List<ProviderStatus> providerStatuses() {
        return trendProviders.stream()
                .map(p -> new ProviderStatus(p.code(), p.displayName(), p.available()))
                .toList();
    }

    public String recommend(String keyword, String topic, String platformCode) {
        String prompt = """
                You are a content strategist. Given this observed trend, suggest 3 original content ideas a business could create.

                Trend keyword: %s
                Trend description: %s
                Platform: %s

                Never suggest copying another creator's footage, voice, music, or script.
                Return a JSON object with:
                {
                  "summary": "1 sentence why this trend matters now",
                  "ideas": [
                    {"type": "REEL/POST/STORY", "title": "content title", "angle": "specific original angle/hook"}
                  ],
                  "bestTime": "recommended publishing window",
                  "warning": "brand safety or copyright concern, or null"
                }
                """.formatted(keyword, topic, platformCode);
        try {
            return stripMarkdown(chatModel.chat(prompt));
        } catch (Exception e) {
            log.warn("AI trend recommendation failed: {}", e.getMessage());
            return "{\"summary\":\"This topic is receiving current interest.\",\"ideas\":[{\"type\":\"REEL\",\"title\":\"Our original take on "
                    + escapeJson(keyword) + "\",\"angle\":\"Connect the topic to a real customer problem\"}],\"bestTime\":\"Test two weekday time slots\",\"warning\":\"Use original assets and licensed music\"}";
        }
    }

    private List<TrendSignalEntity> persistObserved(TenantEntity tenant, TrendProvider.TrendQuery query,
                                                     List<ObservedTrend> observed, String sourceName) {
        TrendSourceEntity source = findOrCreateSource(tenant, sourceName);
        List<TrendSignalEntity> saved = new ArrayList<>();
        for (ObservedTrend trend : observed.stream().limit(query.count()).toList()) {
            TrendSignalEntity signal = TrendSignalEntity.create(
                    tenant,
                    source.getId(),
                    trend.keyword(),
                    trend.hashtag(),
                    trend.topic(),
                    query.country(),
                    query.industry(),
                    query.platformCode(),
                    Math.max(0.0, Math.min(1.0, trend.rawScore()))
            );
            trendScoringService.applyScores(signal);
            saved.add(trendSignalRepository.save(signal));
        }
        log.info("Discovered {} trends. tenant={} provider={} platform={}",
                saved.size(), tenant.getId(), sourceName, query.platformCode());
        return saved;
    }

    private TrendSourceEntity findOrCreateSource(TenantEntity tenant, String sourceName) {
        return trendSourceRepository.findAllByTenantIdAndActive(tenant.getId(), true).stream()
                .filter(s -> sourceName.equalsIgnoreCase(s.getName()))
                .findFirst()
                .orElseGet(() -> trendSourceRepository.save(
                        TrendSourceEntity.create(tenant, sourceName, TrendSourceType.API)));
    }

    private List<ObservedTrend> estimateWithAi(TrendProvider.TrendQuery query) {
        String prompt = """
                Generate %d plausible content topics for brainstorming only.
                These are AI estimates, not verified live platform trends.

                Industry: %s
                Country/Region: %s
                Platform: %s
                Current date: %s

                Return ONLY a JSON array:
                [{
                  "keyword": "topic phrase",
                  "hashtag": "#hashtag",
                  "topic": "why a business could create original content about it",
                  "rawScore": 0.0
                }]
                """.formatted(query.count(), query.industry(), query.country(), query.platformCode(), LocalDate.now());
        try {
            JsonNode array = objectMapper.readTree(stripMarkdown(chatModel.chat(prompt)));
            List<ObservedTrend> results = new ArrayList<>();
            for (JsonNode node : array) {
                results.add(new ObservedTrend(
                        node.path("keyword").asText(),
                        node.path("hashtag").asText(),
                        node.path("topic").asText(),
                        Math.min(0.45, node.path("rawScore").asDouble(0.35)),
                        "AI Estimate"
                ));
            }
            return results;
        } catch (Exception e) {
            log.warn("AI trend estimate failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String stripMarkdown(String text) {
        String value = text == null ? "" : text.trim();
        if (value.startsWith("```")) {
            value = value.replaceAll("(?s)^```\\w*\\n?", "").replaceAll("```$", "").trim();
        }
        int arrayStart = value.indexOf('[');
        int objectStart = value.indexOf('{');
        if (arrayStart >= 0 && (objectStart < 0 || arrayStart < objectStart)) {
            int end = value.lastIndexOf(']');
            return end > arrayStart ? value.substring(arrayStart, end + 1) : value;
        }
        if (objectStart >= 0) {
            int end = value.lastIndexOf('}');
            return end > objectStart ? value.substring(objectStart, end + 1) : value;
        }
        return value;
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public record ProviderStatus(String code, String name, boolean available) {}
}
