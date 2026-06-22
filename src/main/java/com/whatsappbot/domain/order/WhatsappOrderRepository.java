package com.whatsappbot.domain.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface WhatsappOrderRepository extends JpaRepository<WhatsappOrderEntity, UUID> {
    List<WhatsappOrderEntity> findAllByTenantIdOrderByCreatedAtDesc(UUID tenantId);
}
