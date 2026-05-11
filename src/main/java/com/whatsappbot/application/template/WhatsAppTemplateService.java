package com.whatsappbot.application.template;

import com.whatsappbot.domain.conversation.ConversationEntity;
import com.whatsappbot.domain.template.*;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.infrastructure.whatsapp.WhatsAppTemplateGraphClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class WhatsAppTemplateService {

    private final WhatsAppMessageTemplateRepository templateRepository;
    private final WhatsAppTemplateSendAuditRepository auditRepository;
    private final WhatsAppTemplateGraphClient graphClient;

    @Transactional(readOnly = true)
    public List<TemplateSummary> listTemplates(TenantEntity tenant) {
        return templateRepository.findByTenantAndActiveTrueOrderByTemplateCodeAsc(tenant)
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Transactional(readOnly = true)
    public String describeAiEnabledTemplates(TenantEntity tenant) {
        List<WhatsAppMessageTemplate> templates = templateRepository
                .findByTenantAndEnabledForAiTrueAndActiveTrueOrderByTemplateCodeAsc(tenant);
        if (templates.isEmpty()) {
            return "No AI-enabled WhatsApp templates are configured for this business.";
        }
        StringBuilder builder = new StringBuilder("AI-enabled WhatsApp templates for this business:\n");
        for (WhatsAppMessageTemplate template : templates) {
            builder.append("- code=").append(template.getTemplateCode())
                    .append(", audience=").append(template.getAudience())
                    .append(", category=").append(template.getCategory())
                    .append(", language=").append(template.getLanguageCode())
                    .append(", use=").append(nullToEmpty(template.getDescription()))
                    .append(", variables=").append(nullToEmpty(template.getComponentSchemaJson()))
                    .append("\n");
        }
        return builder.toString();
    }

    @Transactional
    public TemplateSendResult sendTemplate(TenantEntity tenant,
                                           ConversationEntity conversation,
                                           String templateCode,
                                           String languageCode,
                                           String toPhoneNumber,
                                           String componentsJson,
                                           List<String> bodyTextParameters,
                                           TemplateRecipientType recipientType,
                                           TemplateSentBy sentBy) {
        WhatsAppMessageTemplate template = resolveTemplate(tenant, templateCode, languageCode);
        validateAudience(template, recipientType);

        TemplateSendResult result = graphClient.sendTemplate(
                tenant,
                template,
                toPhoneNumber,
                componentsJson,
                bodyTextParameters
        );

        auditRepository.save(WhatsAppTemplateSendAudit.create(
                tenant,
                conversation,
                template,
                recipientType,
                toPhoneNumber,
                componentsJson,
                result.graphPayloadJson(),
                result.statusCode(),
                result.responseBody(),
                sentBy
        ));

        return result;
    }

    private WhatsAppMessageTemplate resolveTemplate(TenantEntity tenant, String templateCode, String languageCode) {
        String resolvedLanguageCode = (languageCode == null || languageCode.isBlank())
                ? tenant.getDefaultLanguage()
                : languageCode;
        return templateRepository.findByTenantAndTemplateCodeAndLanguageCodeAndActiveTrue(tenant, templateCode, resolvedLanguageCode)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Template not configured: " + templateCode + "/" + resolvedLanguageCode));
    }

    private void validateAudience(WhatsAppMessageTemplate template, TemplateRecipientType recipientType) {
        if (template.getAudience() == TemplateAudience.CUSTOMER && recipientType == TemplateRecipientType.BUSINESS_CONTACT) {
            throw new IllegalArgumentException("Customer template cannot be sent to business contacts: " + template.getTemplateCode());
        }
        if (template.getAudience() == TemplateAudience.BUSINESS_CONTACT && recipientType == TemplateRecipientType.CUSTOMER) {
            throw new IllegalArgumentException("Business template cannot be sent to customers: " + template.getTemplateCode());
        }
    }

    private TemplateSummary toSummary(WhatsAppMessageTemplate template) {
        return new TemplateSummary(
                template.getTemplateCode(),
                template.getMetaTemplateName(),
                template.getLanguageCode(),
                template.getCategory().name(),
                template.getAudience().name(),
                template.getDescription(),
                template.getBodyPreview(),
                template.getComponentSchemaJson(),
                template.isEnabledForAi()
        );
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
