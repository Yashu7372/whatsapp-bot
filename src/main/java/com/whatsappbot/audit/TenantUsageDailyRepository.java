package com.whatsappbot.audit;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TenantUsageDailyRepository extends JpaRepository<TenantUsageDailyEntity, UUID> {

    Optional<TenantUsageDailyEntity> findByTenantIdAndUsageDate(UUID tenantId, LocalDate usageDate);

    @Query("""
        SELECT u FROM TenantUsageDailyEntity u
        WHERE u.tenant.id = :tenantId
          AND u.usageDate BETWEEN :from AND :to
        ORDER BY u.usageDate ASC
        """)
    List<TenantUsageDailyEntity> findByTenantIdAndDateRange(
            @Param("tenantId") UUID tenantId,
            @Param("from") LocalDate from,
            @Param("to") LocalDate to);
}
