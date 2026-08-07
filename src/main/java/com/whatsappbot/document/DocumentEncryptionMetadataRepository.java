package com.whatsappbot.document;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface DocumentEncryptionMetadataRepository
        extends JpaRepository<DocumentEncryptionMetadataEntity, UUID> {

    Optional<DocumentEncryptionMetadataEntity> findByAssetId(UUID assetId);
}
