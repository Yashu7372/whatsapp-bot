package com.whatsappbot.trend;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TrendSourceRepository extends JpaRepository<TrendSourceEntity, UUID> {
    List<TrendSourceEntity> findAllByTenantIdAndActive(UUID tenantId, boolean active);
}
