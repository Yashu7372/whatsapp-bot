package com.whatsappbot.domain.template;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface WhatsAppTemplateSendAuditRepository extends JpaRepository<WhatsAppTemplateSendAudit, UUID> {
}
