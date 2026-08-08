package com.whatsappbot.document.intake;

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
@Table(name = "document_upload_links")
public class DocumentUploadLinkEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "project_id")
    private UUID projectId;

    @Column(name = "doc_type", nullable = false, length = 100)
    private String docType;

    @Column(name = "label", nullable = false, length = 200)
    private String label;

    @Column(name = "token", nullable = false, unique = true, length = 64)
    private String token;

    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "max_uploads")
    private Integer maxUploads;

    @Column(name = "upload_count", nullable = false)
    private int uploadCount = 0;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "created_by")
    private TenantUserEntity createdBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }

    public boolean requiresPassword() {
        return passwordHash != null && !passwordHash.isBlank();
    }

    public boolean isUsable() {
        if (revokedAt != null) return false;
        if (expiresAt.isBefore(LocalDateTime.now())) return false;
        return maxUploads == null || uploadCount < maxUploads;
    }
}
