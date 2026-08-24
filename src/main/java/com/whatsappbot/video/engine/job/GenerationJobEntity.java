package com.whatsappbot.video.engine.job;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.video.engine.GenerationMode;
import com.whatsappbot.video.engine.GenerationState;
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
@Table(name = "video_generation_jobs")
public class GenerationJobEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "topic", nullable = false, length = 1000)
    private String topic;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 50)
    private GenerationMode mode = GenerationMode.FACELESS;

    @Column(name = "platform", nullable = false, length = 50)
    private String platform = "INSTAGRAM";

    @Column(name = "target_duration_seconds", nullable = false)
    private int targetDurationSeconds = 30;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 50)
    private GenerationState state = GenerationState.INTAKE;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private GenerationJobStatus status = GenerationJobStatus.READY;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "options", nullable = false, columnDefinition = "jsonb")
    private String options = "{}";

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "artifacts", nullable = false, columnDefinition = "jsonb")
    private String artifacts = "[]";

    @Column(name = "last_gate_message", columnDefinition = "TEXT")
    private String lastGateMessage;

    @Column(name = "error_message", columnDefinition = "TEXT")
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
