package com.whatsappbot.analytics;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AnalyticsSnapshotRepository extends JpaRepository<AnalyticsSnapshotEntity, UUID> {
    List<AnalyticsSnapshotEntity> findAllByTenantIdOrderByCapturedAtDesc(UUID tenantId);
}
