package com.whatsappbot.domain.interactive;

import com.whatsappbot.domain.tenant.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WhatsappButtonReplyRepository extends JpaRepository<WhatsappButtonReplyEntity, java.util.UUID> {

    Optional<WhatsappButtonReplyEntity> findByTenantAndButtonIdAndActiveTrue(TenantEntity tenant, String buttonId);

    List<WhatsappButtonReplyEntity> findByTenantAndActiveTrueOrderBySortOrderAsc(TenantEntity tenant);
}