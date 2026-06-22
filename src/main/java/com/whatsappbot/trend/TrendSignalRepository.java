package com.whatsappbot.trend;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TrendSignalRepository extends JpaRepository<TrendSignalEntity, UUID> {
    List<TrendSignalEntity> findAllByTenantIdOrderByFinalScoreDesc(UUID tenantId);
    Page<TrendSignalEntity> findAllByTenantId(UUID tenantId, Pageable pageable);
}
