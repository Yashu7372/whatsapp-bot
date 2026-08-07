package com.whatsappbot.payment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PaymentApplicationItemRepository
        extends JpaRepository<PaymentApplicationItemEntity, UUID> {

    List<PaymentApplicationItemEntity> findAllByTenantIdAndPaymentApplicationIdOrderByCreatedAtAsc(
            UUID tenantId, UUID paymentApplicationId);

    Optional<PaymentApplicationItemEntity> findByIdAndTenantId(UUID id, UUID tenantId);

    boolean existsByPaymentApplicationIdAndDocumentId(UUID paymentApplicationId, UUID documentId);
}
