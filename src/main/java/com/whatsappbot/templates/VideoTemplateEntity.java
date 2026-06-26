package com.whatsappbot.templates;

import com.whatsappbot.domain.tenant.TenantEntity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "video_templates")
public class VideoTemplateEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "tenant_id")
    private TenantEntity tenant;

    @Column(name = "scope", nullable = false, length = 50)
    private String scope = "SYSTEM";

    @Column(name = "name", nullable = false, length = 300)
    private String name;

    @Column(name = "category", length = 200)
    private String category;

    @Column(name = "format", nullable = false, length = 50)
    private String format;

    @Column(name = "template_asset_id")
    private UUID templateAssetId;

    @Column(name = "preview_asset_id")
    private UUID previewAssetId;

    @Column(name = "thumbnail_asset_id")
    private UUID thumbnailAssetId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(name = "active", nullable = false)
    private boolean active = true;

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
