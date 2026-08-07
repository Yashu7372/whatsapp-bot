package com.whatsappbot.publisher;

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
@Table(name = "publishing_jobs")
public class PublishingJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "content_item_id", nullable = false)
    private UUID contentItemId;

    @Column(name = "social_account_id", nullable = false)
    private UUID socialAccountId;

    @Column(name = "platform", nullable = false, length = 100)
    private String platform;

    @Column(name = "asset_id")
    private UUID assetId;

    @Column(name = "caption", columnDefinition = "text")
    private String caption;

    @Column(name = "hashtags", columnDefinition = "text[]")
    private String[] hashtags = new String[0];

    @Column(name = "scheduled_at", nullable = false)
    private LocalDateTime scheduledAt;

    @Column(name = "status", nullable = false, length = 50)
    private String status = "SCHEDULED";

    @Column(name = "external_post_id", length = 300)
    private String externalPostId;

    @Column(name = "external_post_url", columnDefinition = "text")
    private String externalPostUrl;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

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
