package com.whatsappbot.publisher;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SocialAccountRepository extends JpaRepository<SocialAccountEntity, UUID> {

    List<SocialAccountEntity> findAllByTenantIdAndStatusOrderByCreatedAtDesc(UUID tenantId, String status);

    Optional<SocialAccountEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    List<SocialAccountEntity> findAllByTenantId(UUID tenantId);
}
