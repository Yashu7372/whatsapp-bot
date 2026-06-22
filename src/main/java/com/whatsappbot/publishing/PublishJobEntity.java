package com.whatsappbot.publishing;

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
@Table(name = "publish_jobs")
public class PublishJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "content_idea_id", nullable = false)
    private UUID contentIdeaId;

    @Column(name = "platform_account_id", nullable = false)
    private UUID platformAccountId;

    @Column(name = "platform_code", nullable = false, length = 50)
    private String platformCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private PublishStatus status;

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "content_snapshot", columnDefinition = "jsonb")
    private String contentSnapshot;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public static PublishJobEntity create(TenantEntity tenant, UUID contentIdeaId, UUID platformAccountId,
                                          String platformCode, LocalDateTime scheduledAt, String contentSnapshot) {
        PublishJobEntity job = new PublishJobEntity();
        job.setTenant(tenant);
        job.setContentIdeaId(contentIdeaId);
        job.setPlatformAccountId(platformAccountId);
        job.setPlatformCode(platformCode);
        job.setScheduledAt(scheduledAt);
        job.setContentSnapshot(contentSnapshot);
        job.setStatus(PublishStatus.SCHEDULED);
        return job;
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
