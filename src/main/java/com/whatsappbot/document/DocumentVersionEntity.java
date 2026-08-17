package com.whatsappbot.document;

import com.whatsappbot.auth.TenantUserEntity;
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
@Table(name = "document_versions")
public class DocumentVersionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "document_id", nullable = false)
    private UUID documentId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "version_num", nullable = false)
    private int versionNum;

    @Column(name = "asset_id")
    private UUID assetId;

    @Column(name = "change_notes", columnDefinition = "text")
    private String changeNotes;

    @Column(name = "revision_code", nullable = false, length = 30)
    private String revisionCode;

    @Column(name = "issue_status", nullable = false, length = 30)
    private String issueStatus = "DRAFT";

    @Column(name = "issue_purpose", length = 40)
    private String issuePurpose;

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

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
        if (revisionCode == null) revisionCode = String.format("%02d", versionNum);
    }
}
