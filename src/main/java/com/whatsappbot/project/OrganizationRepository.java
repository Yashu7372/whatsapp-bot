package com.whatsappbot.project;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationRepository extends JpaRepository<OrganizationEntity, UUID> {

    List<OrganizationEntity> findAllByTenantIdOrderByNameAsc(UUID tenantId);

    List<OrganizationEntity> findAllByTenantIdAndActiveTrueOrderByNameAsc(UUID tenantId);

    Optional<OrganizationEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    Optional<OrganizationEntity> findByTenantIdAndOrgCodeIgnoreCase(UUID tenantId, String orgCode);

    boolean existsByTenantIdAndOrgCodeIgnoreCase(UUID tenantId, String orgCode);
}
