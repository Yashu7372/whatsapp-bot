package com.whatsappbot.audit;

import com.whatsappbot.domain.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "tenant_usage_daily",
       uniqueConstraints = @UniqueConstraint(columnNames = {"tenant_id", "usage_date"}))
public class TenantUsageDailyEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "usage_date", nullable = false)
    private LocalDate usageDate;

    @Column(name = "storage_bytes", nullable = false)
    private long storageBytes = 0;

    @Column(name = "bandwidth_bytes", nullable = false)
    private long bandwidthBytes = 0;

    @Column(name = "ai_tokens", nullable = false)
    private long aiTokens = 0;

    @Column(name = "render_seconds", nullable = false)
    private long renderSeconds = 0;

    @Column(name = "generated_assets_count", nullable = false)
    private int generatedAssetsCount = 0;

    @Column(name = "scheduled_posts_count", nullable = false)
    private int scheduledPostsCount = 0;

    @Column(name = "published_posts_count", nullable = false)
    private int publishedPostsCount = 0;

    @Column(name = "document_count", nullable = false)
    private int documentCount = 0;
}
