package com.whatsappbot.trend;

import com.whatsappbot.domain.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "trend_signals")
public class TrendSignalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(name = "keyword", length = 200)
    private String keyword;

    @Column(name = "hashtag", length = 200)
    private String hashtag;

    @Column(name = "topic", columnDefinition = "TEXT")
    private String topic;

    @Column(name = "country", length = 10)
    private String country;

    @Column(name = "industry", length = 100)
    private String industry;

    @Column(name = "platform_code", length = 50)
    private String platformCode;

    @Column(name = "raw_score", nullable = false)
    private double rawScore;

    @Column(name = "final_score", nullable = false)
    private double finalScore;

    @Column(name = "freshness_score", nullable = false)
    private double freshnessScore;

    @Column(name = "growth_score", nullable = false)
    private double growthScore;

    @Column(name = "relevance_score", nullable = false)
    private double relevanceScore;

    @Column(name = "engagement_score", nullable = false)
    private double engagementScore;

    @Column(name = "brand_safety_score", nullable = false)
    private double brandSafetyScore;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static TrendSignalEntity create(TenantEntity tenant, UUID sourceId, String keyword, String hashtag,
                                           String topic, String country, String industry, String platformCode,
                                           double rawScore) {
        TrendSignalEntity signal = new TrendSignalEntity();
        signal.setTenant(tenant);
        signal.setSourceId(sourceId);
        signal.setKeyword(keyword);
        signal.setHashtag(hashtag);
        signal.setTopic(topic);
        signal.setCountry(country);
        signal.setIndustry(industry);
        signal.setPlatformCode(platformCode);
        signal.setRawScore(rawScore);
        return signal;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        if (capturedAt == null) {
            capturedAt = now;
        }
    }
}
