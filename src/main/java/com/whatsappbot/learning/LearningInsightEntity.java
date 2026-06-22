package com.whatsappbot.learning;

import com.whatsappbot.domain.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "learning_insights")
public class LearningInsightEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "insight_type", nullable = false, length = 100)
    private String insightType;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "summary", columnDefinition = "TEXT", nullable = false)
    private String summary;

    @Column(name = "recommendation", columnDefinition = "TEXT")
    private String recommendation;

    @Column(name = "confidence_score", nullable = false)
    private double confidenceScore;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "supporting_data", columnDefinition = "jsonb")
    private String supportingData = "{}";

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static LearningInsightEntity create(TenantEntity tenant, String insightType, String title,
                                               String summary, String recommendation, double confidenceScore) {
        LearningInsightEntity insight = new LearningInsightEntity();
        insight.setTenant(tenant);
        insight.setInsightType(insightType);
        insight.setTitle(title);
        insight.setSummary(summary);
        insight.setRecommendation(recommendation);
        insight.setConfidenceScore(confidenceScore);
        insight.setSupportingData("{}");
        insight.setGeneratedAt(LocalDateTime.now());
        return insight;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        if (generatedAt == null) {
            generatedAt = now;
        }
    }
}
