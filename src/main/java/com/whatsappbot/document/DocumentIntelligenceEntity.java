package com.whatsappbot.document;

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
@Table(name = "document_intelligence",
        uniqueConstraints = @UniqueConstraint(name = "uq_document_intelligence_version",
                columnNames = {"tenant_id", "document_id", "version_num"}))
public class DocumentIntelligenceEntity {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @Column(name = "version_num", nullable = false)
    private int versionNum;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(nullable = false, length = 40)
    private String provider;

    @Column(name = "model_name", nullable = false, length = 120)
    private String modelName;

    @Column(name = "result_json", columnDefinition = "text")
    private String resultJson;

    @Column(name = "error_message", columnDefinition = "text")
    private String errorMessage;

    @Column(name = "analyzed_by")
    private UUID analyzedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @PrePersist
    void prePersist() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
