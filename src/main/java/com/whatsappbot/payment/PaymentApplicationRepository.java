package com.whatsappbot.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentApplicationRepository extends JpaRepository<PaymentApplicationEntity, UUID> {

    List<PaymentApplicationEntity> findAllByTenantIdAndProjectIdOrderByCreatedAtDesc(UUID tenantId, UUID projectId);

    List<PaymentApplicationEntity> findAllByTenantIdAndProjectIdAndClaimedByOrgIdOrderByCreatedAtDesc(
            UUID tenantId, UUID projectId, UUID claimedByOrgId);

    Optional<PaymentApplicationEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentApplicationEntity p WHERE p.id = :id")
    Optional<PaymentApplicationEntity> lockById(@Param("id") UUID id);

    @Query("""
            SELECT COUNT(i) FROM PaymentApplicationItemEntity i, PaymentApplicationEntity p
            WHERE i.paymentApplicationId = p.id
              AND i.documentId = :documentId
              AND p.tenant.id = :tenantId
              AND p.id <> :excludingApplicationId
              AND p.status <> com.whatsappbot.payment.PaymentApplicationStatus.REJECTED
            """)
    long countLiveClaimsOnDocument(@Param("tenantId") UUID tenantId,
                                    @Param("documentId") UUID documentId,
                                    @Param("excludingApplicationId") UUID excludingApplicationId);

    boolean existsByProjectIdAndApplicationRefIgnoreCase(UUID projectId, String applicationRef);

    @Query("""
            SELECT COALESCE(SUM(p.netCertified), 0) FROM PaymentApplicationEntity p
            WHERE p.tenant.id = :tenantId
              AND p.projectId = :projectId
              AND p.claimedByOrg.id = :orgId
              AND p.status IN (com.whatsappbot.payment.PaymentApplicationStatus.CERTIFIED,
                               com.whatsappbot.payment.PaymentApplicationStatus.PAID)
            """)
    BigDecimal sumCertifiedToDate(@Param("tenantId") UUID tenantId,
                                   @Param("projectId") UUID projectId,
                                   @Param("orgId") UUID orgId);
}
