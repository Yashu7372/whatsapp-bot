package com.whatsappbot.video.image;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.storage.MediaAssetEntity;
import com.whatsappbot.video.VideoScriptEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "storyboard_image_jobs")
public class StoryboardImageJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "video_script_id", nullable = false)
    private VideoScriptEntity videoScript;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "character_profile_id")
    private CharacterProfileEntity characterProfile;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "output_asset_id")
    private MediaAssetEntity outputAsset;

    @Column(name = "shot_index", nullable = false)
    private int shotIndex;

    @Column(name = "prompt", nullable = false, columnDefinition = "TEXT")
    private String prompt;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality_mode", nullable = false, length = 30)
    private StoryboardQualityMode qualityMode;

    @Column(name = "provider", nullable = false, length = 50)
    private String provider = "ROUTER";

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private StoryboardImageStatus status = StoryboardImageStatus.QUEUED;

    @Column(name = "estimated_cost_usd", nullable = false, precision = 10, scale = 4)
    private BigDecimal estimatedCostUsd = BigDecimal.ZERO;

    @Column(name = "actual_cost_usd", nullable = false, precision = 10, scale = 4)
    private BigDecimal actualCostUsd = BigDecimal.ZERO;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

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
