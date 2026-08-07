package com.whatsappbot.subscription;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface TenantSubscriptionRepository extends JpaRepository<TenantSubscriptionEntity, UUID> {

    Optional<TenantSubscriptionEntity> findTopByTenantIdOrderByStartedAtDesc(UUID tenantId);

    @Query("""
        SELECT CASE WHEN COUNT(s) > 0 THEN true ELSE false END
        FROM TenantSubscriptionEntity s
        WHERE s.tenant.id = :tenantId
          AND s.status IN ('TRIAL', 'ACTIVE')
          AND (s.expiresAt IS NULL OR s.expiresAt > CURRENT_TIMESTAMP)
        """)
    boolean existsActiveSubscription(@Param("tenantId") UUID tenantId);
}
