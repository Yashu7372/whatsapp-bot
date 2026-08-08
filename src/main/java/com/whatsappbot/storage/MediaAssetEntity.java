package com.whatsappbot.storage;

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
@Table(name = "media_assets")
public class MediaAssetEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "original_name", nullable = false, length = 500)
    private String originalName;

    @Column(name = "stored_path", nullable = false, length = 1000)
    private String storedPath;

    @Column(name = "content_type", nullable = false, length = 255)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "asset_type", nullable = false, length = 100)
    private String assetType = "DOCUMENT";

    @Column(name = "ref_id")
    private UUID refId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "uploaded_by")
    private TenantUserEntity uploadedBy;

    // Object storage fields (V17)
    @Column(name = "storage_provider", nullable = false, length = 100)
    private String storageProvider = "LOCAL";

    @Column(name = "bucket_name", length = 255)
    private String bucketName;

    @Column(name = "object_key")
    private String objectKey;

    @Column(name = "checksum_sha256", length = 128)
    private String checksumSha256;

    @Column(name = "visibility", nullable = false, length = 50)
    private String visibility = "PRIVATE";

    @Column(name = "status", nullable = false, length = 50)
    private String status = "UPLOADED";

    @Column(name = "scan_status", nullable = false, length = 30)
    private String scanStatus = "CLEAN";

    @Column(name = "scanned_at")
    private LocalDateTime scannedAt;

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
