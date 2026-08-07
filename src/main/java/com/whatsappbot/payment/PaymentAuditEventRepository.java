package com.whatsappbot.payment;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentAuditEventRepository extends JpaRepository<PaymentAuditEventEntity, UUID> {

    List<PaymentAuditEventEntity> findAllByTenantIdAndPaymentApplicationIdOrderByCreatedAtAsc(
            UUID tenantId, UUID paymentApplicationId);

    @Query("""
            SELECT e FROM PaymentAuditEventEntity e
            WHERE e.tenant.id = :tenantId AND e.paymentApplicationId = :applicationId
            ORDER BY e.createdAt DESC LIMIT 1
            """)
    Optional<PaymentAuditEventEntity> findLatest(@Param("tenantId") UUID tenantId,
                                                  @Param("applicationId") UUID applicationId);
}
