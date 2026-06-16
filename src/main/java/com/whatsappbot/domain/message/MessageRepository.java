package com.whatsappbot.domain.message;

import com.whatsappbot.domain.tenant.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, UUID> {
    boolean existsByTenantAndWaMessageId(TenantEntity tenant, String waMessageId);
}
