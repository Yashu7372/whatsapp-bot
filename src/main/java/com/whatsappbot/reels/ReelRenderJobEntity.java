package com.whatsappbot.reels;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.video.VideoScriptEntity;
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
@Table(name = "reel_render_jobs")
public class ReelRenderJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_script_id", nullable = false)
    private VideoScriptEntity videoScript;

    @Column(name = "template_code", nullable = false, length = 100)
    private String templateCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private ReelRenderStatus status = ReelRenderStatus.PENDING;

    @Column(name = "voice", length = 100)
    private String voice;

    @Column(name = "include_voice", nullable = false)
    private boolean includeVoice;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "asset_ids", columnDefinition = "jsonb", nullable = false)
    private String assetIds = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "asset_urls", columnDefinition = "jsonb", nullable = false)
    private String assetUrls = "[]";

    @Column(name = "output_stored_path", length = 1000)
    private String outputStoredPath;

    @Column(name = "output_size_bytes")
    private Long outputSizeBytes;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "attempts", nullable = false)
    private int attempts;

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
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
