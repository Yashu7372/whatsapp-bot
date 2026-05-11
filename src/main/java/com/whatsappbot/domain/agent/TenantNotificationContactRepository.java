package com.whatsappbot.domain.agent;

import com.whatsappbot.domain.tenant.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TenantNotificationContactRepository extends JpaRepository<TenantNotificationContact, UUID> {
    List<TenantNotificationContact> findByTenantAndPurposeAndActiveTrue(TenantEntity tenant, String purpose);
}
