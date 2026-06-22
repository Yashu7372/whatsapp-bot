package com.whatsappbot.learning;

import com.whatsappbot.analytics.AnalyticsSnapshotEntity;
import com.whatsappbot.domain.tenant.TenantEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class LearningInsightService {

    private final LearningInsightRepository learningInsightRepository;

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

    @Transactional(readOnly = true)
    public List<LearningInsightEntity> listByTenant(UUID tenantId) {
        return learningInsightRepository.findAllByTenantIdOrderByGeneratedAtDesc(tenantId);
    }
}
