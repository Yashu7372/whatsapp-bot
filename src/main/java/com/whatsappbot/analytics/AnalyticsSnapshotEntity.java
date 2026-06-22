package com.whatsappbot.analytics;

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
@Table(name = "analytics_snapshots")
public class AnalyticsSnapshotEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "publish_job_id")
    private UUID publishJobId;

    @Column(name = "platform_code", nullable = false, length = 50)
    private String platformCode;

    @Column(name = "views", nullable = false)
    private long views;

    @Column(name = "likes", nullable = false)
    private long likes;

    @Column(name = "comments", nullable = false)
    private long comments;

    @Column(name = "shares", nullable = false)
    private long shares;

    @Column(name = "clicks", nullable = false)
    private long clicks;

    @Column(name = "leads", nullable = false)
    private long leads;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public static AnalyticsSnapshotEntity create(TenantEntity tenant, UUID publishJobId, String platformCode,
                                                  long views, long likes, long comments, long shares,
                                                  long clicks, long leads) {
        AnalyticsSnapshotEntity snapshot = new AnalyticsSnapshotEntity();
        snapshot.setTenant(tenant);
        snapshot.setPublishJobId(publishJobId);
        snapshot.setPlatformCode(platformCode);
        snapshot.setViews(views);
        snapshot.setLikes(likes);
        snapshot.setComments(comments);
        snapshot.setShares(shares);
        snapshot.setClicks(clicks);
        snapshot.setLeads(leads);
        return snapshot;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        capturedAt = now;
    }
}
