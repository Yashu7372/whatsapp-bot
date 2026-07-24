package com.whatsappbot.video.image;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CharacterProfileRepository extends JpaRepository<CharacterProfileEntity, UUID> {
    List<CharacterProfileEntity> findAllByTenantIdAndActiveTrueOrderByCreatedAtDesc(UUID tenantId);
    Optional<CharacterProfileEntity> findByIdAndTenantIdAndActiveTrue(UUID id, UUID tenantId);
}
