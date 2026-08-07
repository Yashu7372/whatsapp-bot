package com.whatsappbot.document;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.storage.MediaAssetEntity;
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
@Table(name = "document_encryption_metadata")
public class DocumentEncryptionMetadataEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "asset_id", nullable = false)
    private MediaAssetEntity asset;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tenant_id", nullable = false)
    private TenantEntity tenant;

    @Column(name = "encryption_alg", nullable = false, length = 100)
    private String encryptionAlg = "AES-GCM-256";

    @Column(name = "key_id", length = 200)
    private String keyId;

    @Column(name = "encrypted_file_key", columnDefinition = "text")
    private String encryptedFileKey;

    @Column(name = "iv_base64", nullable = false, columnDefinition = "text")
    private String ivBase64;

    @Column(name = "auth_tag_base64", columnDefinition = "text")
    private String authTagBase64;

    @Column(name = "ciphertext_sha256", nullable = false, length = 128)
    private String ciphertextSha256;

    @Column(name = "plaintext_sha256", length = 128)
    private String plaintextSha256;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        createdAt = LocalDateTime.now();
    }
}
