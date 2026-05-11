package com.whatsappbot.domain.template;

import com.whatsappbot.domain.tenant.TenantEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WhatsAppMessageTemplateRepository extends JpaRepository<WhatsAppMessageTemplate, UUID> {

    Optional<WhatsAppMessageTemplate> findByTenantAndTemplateCodeAndLanguageCodeAndActiveTrue(
            TenantEntity tenant, String templateCode, String languageCode);

    Optional<WhatsAppMessageTemplate> findByTenantAndTemplateCodeAndActiveTrue(
            TenantEntity tenant, String templateCode);

    List<WhatsAppMessageTemplate> findByTenantAndActiveTrueOrderByTemplateCodeAsc(TenantEntity tenant);

    List<WhatsAppMessageTemplate> findByTenantAndEnabledForAiTrueAndActiveTrueOrderByTemplateCodeAsc(TenantEntity tenant);
}
