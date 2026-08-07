package com.whatsappbot.features;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface TenantFeatureRepository extends JpaRepository<TenantFeatureEntity, UUID> {

    List<TenantFeatureEntity> findAllByTenantId(UUID tenantId);

    boolean existsByTenantIdAndFeatureCodeAndEnabledTrue(UUID tenantId, String featureCode);

    @Query("SELECT f.featureCode FROM TenantFeatureEntity f WHERE f.tenant.id = :tenantId AND f.enabled = true")
    List<String> findEnabledFeatureCodes(@Param("tenantId") UUID tenantId);
}
