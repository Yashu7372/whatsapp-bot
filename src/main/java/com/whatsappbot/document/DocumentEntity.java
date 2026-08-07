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
import java.util.List;
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

    /** The project this document belongs to. Null for documents not tied to project delivery. */
    @Column(name = "project_id")
    private UUID projectId;

    /** The company that issued the document — the side of the contract it came from. */
    @Column(name = "originator_org_id")
    private UUID originatorOrgId;

    /** Human-facing reference such as ACME-RFI-0042, unique within the project. */
    @Column(name = "document_code", length = 80)
    private String documentCode;

    /** Contractual response deadline for whoever currently holds the document. */
    @Column(name = "due_at")
    private LocalDateTime dueAt;

    /**
     * Value of work this document evidences. When set, a payment claim against it cannot exceed
     * this — without a ceiling the only check on a claimed amount is that it is not negative.
     */
    @Column(name = "approved_value", precision = 18, scale = 2)
    private java.math.BigDecimal approvedValue;

    /**
     * The reviewer's return code. Held separately from {@link #status} because a document
     * returned CODE_B or CODE_C is neither plainly approved nor plainly rejected.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "review_outcome", length = 20)
    private ReviewOutcome reviewOutcome;

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
