package com.whatsappbot.trend;

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
public class TrendImportService {

    private final TrendSourceRepository trendSourceRepository;
    private final TrendSignalRepository trendSignalRepository;
    private final TrendScoringService trendScoringService;

    @Transactional
    public TrendSignalEntity importSignal(TenantEntity tenant, String keyword, String hashtag, String topic,
                                          String country, String industry, String platformCode, double rawScore) {
        // Get or create MANUAL trend source for this tenant
        List<TrendSourceEntity> sources = trendSourceRepository.findAllByTenantIdAndActive(tenant.getId(), true);
        TrendSourceEntity manualSource = sources.stream()
                .filter(s -> TrendSourceType.MANUAL.equals(s.getSourceType()))
                .findFirst()
                .orElseGet(() -> {
                    TrendSourceEntity newSource = TrendSourceEntity.create(tenant, "Manual Import", TrendSourceType.MANUAL);
                    return trendSourceRepository.save(newSource);
                });

        TrendSignalEntity signal = TrendSignalEntity.create(tenant, manualSource.getId(), keyword, hashtag,
                topic, country, industry, platformCode, rawScore);
        trendScoringService.applyScores(signal);
        TrendSignalEntity saved = trendSignalRepository.save(signal);
        log.info("Imported trend signal id={} tenant={} keyword={}", saved.getId(), tenant.getId(), keyword);
        return saved;
    }
}
