package com.whatsappbot.learning;

import com.whatsappbot.analytics.AnalyticsSnapshotEntity;
import com.whatsappbot.analytics.AnalyticsSnapshotRepository;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class LearningInsightService {

    private final LearningInsightRepository learningInsightRepository;
    private final AnalyticsSnapshotRepository analyticsSnapshotRepository;
    private final TenantRepository tenantRepository;
    private final ChatModel chatModel;

    @Transactional
    public void generateInsightIfReady(TenantEntity tenant, AnalyticsSnapshotEntity snapshot) {
        if (snapshot.getViews() > 1000) {
            LearningInsightEntity insight = LearningInsightEntity.create(
                    tenant,
                    "HIGH_REACH",
                    "High Reach Content",
                    "This content achieved over 1000 views, indicating strong reach performance.",
                    "Replicate the format and topic of this content in future campaigns.",
                    0.85
            );
            learningInsightRepository.save(insight);
            log.info("Generated HIGH_REACH insight for tenant={} views={}", tenant.getId(), snapshot.getViews());
        }
        if (snapshot.getLikes() > 100) {
            LearningInsightEntity insight = LearningInsightEntity.create(
                    tenant,
                    "HIGH_ENGAGEMENT",
                    "High Engagement Post",
                    "This content achieved over 100 likes, indicating strong audience engagement.",
                    "Use similar content style and tone in upcoming posts for better engagement.",
                    0.80
            );
            learningInsightRepository.save(insight);
            log.info("Generated HIGH_ENGAGEMENT insight for tenant={} likes={}", tenant.getId(), snapshot.getLikes());
        }
    }

    @Transactional
    public List<LearningInsightEntity> generateForTenant(UUID tenantId) {
        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Tenant not found: " + tenantId));
        List<AnalyticsSnapshotEntity> snapshots =
                analyticsSnapshotRepository.findAllByTenantIdOrderByCapturedAtDesc(tenantId);
        if (snapshots.isEmpty()) {
            return List.of();
        }
        String summary = snapshots.stream()
                .limit(10)
                .map(s -> String.format("platform=%s views=%d likes=%d comments=%d shares=%d leads=%d",
                        s.getPlatformCode(), s.getViews(), s.getLikes(),
                        s.getComments(), s.getShares(), s.getLeads()))
                .collect(Collectors.joining("\n"));
        String prompt = """
                You are a social media analytics expert. Analyze the following recent performance data and return exactly 3 concise insights in JSON array format.
                Each object must have: insightType (string), title (string, max 60 chars), summary (string, max 200 chars), recommendation (string, max 200 chars), confidenceScore (number 0-1).

                Data:
                %s

                Return only the JSON array, no markdown, no explanation.
                """.formatted(summary);
        List<LearningInsightEntity> generated = new java.util.ArrayList<>();
        try {
            String response = chatModel.chat(prompt);
            String json = response.trim();
            if (json.startsWith("```")) {
                json = json.replaceAll("```json?", "").replace("```", "").trim();
            }
            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode arr = mapper.readTree(json);
            for (com.fasterxml.jackson.databind.JsonNode node : arr) {
                LearningInsightEntity insight = LearningInsightEntity.create(
                        tenant,
                        node.path("insightType").asText("AI_INSIGHT"),
                        node.path("title").asText("Insight"),
                        node.path("summary").asText(""),
                        node.path("recommendation").asText(""),
                        node.path("confidenceScore").asDouble(0.75)
                );
                learningInsightRepository.save(insight);
                generated.add(insight);
            }
            log.info("Generated {} AI insights for tenant={}", generated.size(), tenantId);
        } catch (Exception e) {
            log.warn("AI insight generation failed for tenant={}, falling back to rule-based: {}", tenantId, e.getMessage());
            snapshots.stream().limit(5).forEach(s -> generateInsightIfReady(tenant, s));
        }
        return generated.isEmpty() ? listByTenant(tenantId) : generated;
    }

    @Transactional(readOnly = true)
    public List<LearningInsightEntity> listByTenant(UUID tenantId) {
        return learningInsightRepository.findAllByTenantIdOrderByGeneratedAtDesc(tenantId);
    }
}
