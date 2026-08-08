package com.whatsappbot.document;

import com.whatsappbot.auth.TenantUserEntity;
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
@Table(name = "documents")
public class DocumentEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "title", nullable = false, length = 500)
    private String title;

    @Column(name = "doc_type", nullable = false, length = 100)
    private String docType = "GENERAL";

    @Column(name = "description", columnDefinition = "text")
    private String description;

    @JdbcTypeCode(SqlTypes.ARRAY)
    @Column(name = "tags", columnDefinition = "text[]")
    private String[] tags;

    @Column(name = "current_version", nullable = false)
    private int currentVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 50)
    private DocumentStatus status = DocumentStatus.DRAFT;

    @Column(name = "workflow_id")
    private UUID workflowId;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "originator_org_id")
    private UUID originatorOrgId;

    @Column(name = "document_code", length = 80)
    private String documentCode;

    @Column(name = "due_at")
    private LocalDateTime dueAt;

    @Column(name = "approved_value", precision = 18, scale = 2)
    private java.math.BigDecimal approvedValue;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_outcome", length = 20)
    private ReviewOutcome reviewOutcome;

    @Column(name = "security_classification", nullable = false, length = 30)
    private String securityClassification = "PROJECT";

    @Column(name = "discipline", length = 80)
    private String discipline;

    @Column(name = "package_code", length = 80)
    private String packageCode;

    @Column(name = "location_code", length = 80)
    private String locationCode;

    @Column(name = "issue_purpose", length = 40)
    private String issuePurpose;

    @Column(name = "current_revision_code", nullable = false, length = 30)
    private String currentRevisionCode = "01";

    @Column(name = "issued_at")
    private LocalDateTime issuedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "issued_by")
    private TenantUserEntity issuedBy;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private TenantUserEntity createdBy;

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
