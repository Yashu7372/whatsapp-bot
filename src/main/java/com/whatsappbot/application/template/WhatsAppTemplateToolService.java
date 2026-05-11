package com.whatsappbot.application.template;

import com.whatsappbot.application.context.TenantExecutionContext;
import com.whatsappbot.domain.agent.TenantNotificationContact;
import com.whatsappbot.domain.agent.TenantNotificationContactRepository;
import com.whatsappbot.domain.template.TemplateRecipientType;
import com.whatsappbot.domain.template.TemplateSentBy;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@Component("whatsAppTemplateTools")
@RequiredArgsConstructor
public class WhatsAppTemplateToolService {

    private final WhatsAppTemplateService templateService;
    private final TenantNotificationContactRepository notificationContactRepository;

    @Tool("List the approved WhatsApp message templates that this business allows the AI to use. Use this before sending a template if unsure.")
    public String listAvailableTemplates() {
        TenantExecutionContext.Context context = TenantExecutionContext.getRequired();
        return templateService.describeAiEnabledTemplates(context.tenant());
    }

    @Tool("Send an approved WhatsApp template to the current customer. Use only when the customer explicitly needs a structured template such as order update, appointment confirmation, re-engagement, payment reminder, or approved marketing message. componentsJson must be a WhatsApp Graph API template components array as JSON, or leave blank and use bodyTextParameters.")
    public String sendTemplateToCurrentCustomer(String templateCode,
                                                String languageCode,
                                                String componentsJson,
                                                List<String> bodyTextParameters) {
        TenantExecutionContext.Context context = TenantExecutionContext.getRequired();
        TemplateSendResult result = templateService.sendTemplate(
                context.tenant(),
                context.conversation(),
                templateCode,
                languageCode,
                context.customerPhoneNumber(),
                normalizeJson(componentsJson),
                bodyTextParameters,
                TemplateRecipientType.CUSTOMER,
                TemplateSentBy.AI_TOOL
        );
        return result.sent()
                ? "TEMPLATE_SENT_TO_CUSTOMER: " + templateCode
                : "TEMPLATE_SEND_FAILED: status=" + result.statusCode() + ", body=" + result.responseBody();
    }

    @Tool("Notify configured business users using an approved WhatsApp business-contact template. Use this for internal alerts such as new lead, human handoff, urgent customer issue, booking request, or order exception. contactPurpose examples: SALES, SUPPORT, ORDERS, MANAGER. componentsJson must be a WhatsApp Graph API template components array as JSON, or leave blank and use bodyTextParameters.")
    public String sendTemplateToBusinessContacts(String templateCode,
                                                 String languageCode,
                                                 String contactPurpose,
                                                 String componentsJson,
                                                 List<String> bodyTextParameters) {
        TenantExecutionContext.Context context = TenantExecutionContext.getRequired();
        List<TenantNotificationContact> contacts = notificationContactRepository
                .findByTenantAndPurposeAndActiveTrue(context.tenant(), contactPurpose);
        if (contacts.isEmpty()) {
            return "NO_BUSINESS_CONTACT_CONFIGURED_FOR_PURPOSE: " + contactPurpose;
        }

        int sent = 0;
        StringBuilder failures = new StringBuilder();
        for (TenantNotificationContact contact : contacts) {
            TemplateSendResult result = templateService.sendTemplate(
                    context.tenant(),
                    context.conversation(),
                    templateCode,
                    languageCode,
                    contact.getPhoneNumber(),
                    normalizeJson(componentsJson),
                    bodyTextParameters,
                    TemplateRecipientType.BUSINESS_CONTACT,
                    TemplateSentBy.AI_TOOL
            );
            if (result.sent()) {
                sent++;
            } else {
                failures.append("[").append(contact.getPhoneNumber()).append(": ")
                        .append(result.statusCode()).append("] ");
            }
        }
        return "BUSINESS_TEMPLATE_SEND_RESULT: sent=" + sent + ", failed=" + (contacts.size() - sent) + " " + failures;
    }

    private String normalizeJson(String componentsJson) {
        return componentsJson == null || componentsJson.isBlank() ? null : componentsJson;
    }
}
