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

    List<PaymentApplicationEntity> findAllByTenantIdAndProjectIdOrderByCreatedAtDesc(UUID tenantId,
                                                                                      UUID projectId);

    Optional<PaymentApplicationEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Serialisation point for appending to a claim's audit chain. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM PaymentApplicationEntity p WHERE p.id = :id")
    Optional<PaymentApplicationEntity> lockById(@Param("id") UUID id);

    /**
     * Claims that still hold a place on a document — anything not rejected. Used to stop the same
     * approved work being claimed on several applications.
     */
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

    /**
     * Total already certified on this project for this claimant, which becomes the new claim's
     * opening position. Returns zero rather than null when nothing has been certified yet.
     */
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
