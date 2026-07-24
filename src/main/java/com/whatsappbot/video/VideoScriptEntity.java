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
@Table(name = "video_scripts")
public class VideoScriptEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "content_idea_id")
    private UUID contentIdeaId;

    @Column(name = "title", nullable = false, length = 300)
    private String title;

    @Column(name = "platform_code", nullable = false, length = 50)
    private String platformCode = "INSTAGRAM";

    @Column(name = "content_type", nullable = false, length = 50)
    private String contentType = "REEL";

    @Column(name = "style", nullable = false, length = 100)
    private String style = "ENGAGING";

    @Column(name = "template_code", nullable = false, length = 100)
    private String templateCode = "TALKING_PRESENTER";

    @Column(name = "duration_secs", nullable = false)
    private int durationSecs = 30;

    @Column(name = "hook", columnDefinition = "TEXT")
    private String hook;

    @Column(name = "script_body", columnDefinition = "TEXT")
    private String scriptBody;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "shot_list", columnDefinition = "jsonb")
    private String shotList = "[]";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "hashtags", columnDefinition = "jsonb")
    private String hashtags = "[]";

    @Column(name = "caption", columnDefinition = "TEXT")
    private String caption;

    @Column(name = "music_suggestion", length = 200)
    private String musicSuggestion;

    @Column(name = "status", nullable = false, length = 50)
    private String status = "DRAFT";

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
        if (generatedAt == null) generatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
