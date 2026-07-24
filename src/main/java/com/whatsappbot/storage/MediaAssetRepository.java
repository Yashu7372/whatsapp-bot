package com.whatsappbot.storage;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface MediaAssetRepository extends JpaRepository<MediaAssetEntity, UUID> {
    List<MediaAssetEntity> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);
    List<MediaAssetEntity> findAllByTenantIdAndRefIdAndAssetTypeOrderByCreatedAtAsc(
            UUID tenantId, UUID refId, String assetType);
    Optional<MediaAssetEntity> findByIdAndTenantId(UUID id, UUID tenantId);
}
