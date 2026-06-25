package com.whatsappbot.trend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.domain.tenant.TenantEntity;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TrendDiscoveryService {

    private final TrendSignalRepository trendSignalRepository;
    private final TrendSourceRepository trendSourceRepository;
    private final TrendScoringService trendScoringService;
    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public List<TrendSignalEntity> discover(TenantEntity tenant, String industry, String country,
                                             String platformCode, int count) {
        String prompt = """
                You are a social media trend analyst. Discover %d currently trending topics for the following context.

                Industry: %s
                Country/Region: %s
                Platform: %s
                Today's date context: Recent trends (assume current year 2025)

                Return ONLY a JSON array (no markdown) of trend objects, each with:
                {
                  "keyword": "main trending keyword or phrase",
                  "hashtag": "#relevanthashtag",
                  "topic": "1-2 sentence description of what this trend is about and why it's trending",
                  "rawScore": 0.0-1.0 (estimated trend strength, higher = stronger),
                  "industry": "%s",
                  "country": "%s"
                }

                Focus on trends that businesses in this industry can actually create content around.
                """.formatted(count, industry, country, platformCode, industry, country);

        List<TrendSignalEntity> results = new ArrayList<>();
        try {
            String raw = chatModel.chat(prompt);
            String json = stripMarkdown(raw);
            JsonNode arr = objectMapper.readTree(json);

            TrendSourceEntity aiSource = trendSourceRepository
                    .findAllByTenantIdAndActive(tenant.getId(), true).stream()
                    .filter(s -> TrendSourceType.API.equals(s.getSourceType()))
                    .findFirst()
                    .orElseGet(() -> {
                        TrendSourceEntity s = TrendSourceEntity.create(tenant, "AI Discovery", TrendSourceType.API);
                        return trendSourceRepository.save(s);
                    });

            for (JsonNode node : arr) {
                TrendSignalEntity signal = TrendSignalEntity.create(
                        tenant,
                        aiSource.getId(),
                        node.path("keyword").asText(null),
                        node.path("hashtag").asText(null),
                        node.path("topic").asText(null),
                        node.path("country").asText(country),
                        node.path("industry").asText(industry),
                        platformCode,
                        node.path("rawScore").asDouble(0.7)
                );
                trendScoringService.applyScores(signal);
                results.add(trendSignalRepository.save(signal));
            }
            log.info("AI discovered {} trends for tenant={} industry={} platform={}", results.size(), tenant.getId(), industry, platformCode);
        } catch (Exception e) {
            log.warn("AI trend discovery failed for tenant={}: {}", tenant.getId(), e.getMessage());
        }
        return results;
    }

    // Per-trend content recommendation
    public String recommend(String keyword, String topic, String platformCode) {
        String prompt = """
                You are a content strategist. Given this social media trend, suggest 3 specific content ideas a business could create.

                Trend keyword: %s
                Trend description: %s
                Platform: %s

                Return a JSON object with:
                {
                  "summary": "1 sentence why this trend matters now",
                  "ideas": [
                    {"type": "REEL/POST/STORY", "title": "content title", "angle": "specific angle/hook to use"},
                    ...
                  ],
                  "bestTime": "best time to post this content",
                  "warning": "any brand safety concern or null"
                }
                """.formatted(keyword, topic, platformCode);
        try {
            String raw = chatModel.chat(prompt);
            return stripMarkdown(raw);
        } catch (Exception e) {
            log.warn("AI trend recommendation failed: {}", e.getMessage());
            return "{\"summary\":\"This trend is gaining traction — act quickly.\",\"ideas\":[{\"type\":\"REEL\",\"title\":\"Our take on " + keyword + "\",\"angle\":\"Share your brand perspective\"}],\"bestTime\":\"Weekday mornings 8-10am\",\"warning\":null}";
        }
    }

    private String stripMarkdown(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            t = t.replaceAll("(?s)^```\\w*\\n?", "").replaceAll("```$", "").trim();
        }
        // find first [ or {
        int arrStart = t.indexOf('[');
        int objStart = t.indexOf('{');
        if (arrStart >= 0 && (objStart < 0 || arrStart < objStart)) {
            return t.substring(arrStart, t.lastIndexOf(']') + 1);
        }
        if (objStart >= 0) {
            return t.substring(objStart, t.lastIndexOf('}') + 1);
        }
        return t;
    }
}
