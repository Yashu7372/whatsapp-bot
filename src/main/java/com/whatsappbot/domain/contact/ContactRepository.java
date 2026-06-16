package com.whatsappbot.domain.contact;

import com.whatsappbot.domain.tenant.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ContactRepository extends JpaRepository<ContactEntity, UUID> {
    Optional<ContactEntity> findByTenantAndWaId(TenantEntity tenant, String waId);

    Optional<ContactEntity> findByTenantAndPhoneNumber(TenantEntity tenant, String phoneNumber);

    List<ContactEntity> findByTenantAndDisplayNameContainingIgnoreCase(TenantEntity tenant, String displayName);
}
