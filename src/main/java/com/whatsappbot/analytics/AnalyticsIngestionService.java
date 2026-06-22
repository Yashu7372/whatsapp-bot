package com.whatsappbot.analytics;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.learning.LearningInsightService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AnalyticsIngestionService {

    private final AnalyticsSnapshotRepository analyticsSnapshotRepository;
    private final LearningInsightService learningInsightService;

    @Transactional
    public AnalyticsSnapshotEntity ingest(TenantEntity tenant, UUID publishJobId, String platformCode,
                                          long views, long likes, long comments, long shares,
                                          long clicks, long leads) {
        AnalyticsSnapshotEntity snapshot = AnalyticsSnapshotEntity.create(
                tenant, publishJobId, platformCode, views, likes, comments, shares, clicks, leads);
        AnalyticsSnapshotEntity saved = analyticsSnapshotRepository.save(snapshot);
        log.info("Ingested analytics snapshot id={} tenant={} platform={} views={}",
                saved.getId(), tenant.getId(), platformCode, views);
        learningInsightService.generateInsightIfReady(tenant, saved);
        return saved;
    }
}
