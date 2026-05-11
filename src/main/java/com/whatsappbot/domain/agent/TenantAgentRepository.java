package com.whatsappbot.domain.agent;

import com.whatsappbot.domain.tenant.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantAgentRepository extends JpaRepository<TenantAgent, UUID> {
    List<TenantAgent> findByTenantAndActiveTrueOrderByNameAsc(TenantEntity tenant);
    Optional<TenantAgent> findByTenantAndIdAndActiveTrue(TenantEntity tenant, UUID id);
}
