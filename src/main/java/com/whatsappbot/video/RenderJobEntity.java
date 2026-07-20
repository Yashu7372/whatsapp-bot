package com.whatsappbot.video;

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
@Table(name = "video_render_jobs")
public class RenderJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "script_id", nullable = false)
    private VideoScriptEntity script;

    @Column(name = "template_code", nullable = false, length = 100)
    private String templateCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "asset_ids", nullable = false, columnDefinition = "jsonb")
    private String assetIds = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "asset_urls", nullable = false, columnDefinition = "jsonb")
    private String assetUrls = "[]";

    @Column(name = "voice", nullable = false, length = 100)
    private String voice = "af_heart";

    @Column(name = "brand_name", length = 200)
    private String brandName;

    @Column(name = "call_to_action", length = 300)
    private String callToAction;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private RenderJobStatus status = RenderJobStatus.QUEUED;

    @Column(name = "progress", nullable = false)
    private int progress;

    @Column(name = "output_path", length = 1000)
    private String outputPath;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "max_retries", nullable = false)
    private int maxRetries = 3;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (nextAttemptAt == null) nextAttemptAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
