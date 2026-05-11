package com.whatsappbot.application.ai;

import com.whatsappbot.application.knowledge.KnowledgeService;
import com.whatsappbot.application.template.WhatsAppTemplateService;
import com.whatsappbot.domain.contact.ContactEntity;
import com.whatsappbot.domain.conversation.ConversationEntity;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.infrastructure.ai.WhatsAppAgent;
import com.whatsappbot.infrastructure.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TenantAiService {

    private static final String GLOBAL_GUARDRAILS = """
            Platform rules:
            - Answer only for the current business.
            - Do not invent products, prices, availability, policies, discounts, or commitments.
            - Keep the response short and suitable for WhatsApp.
            - If the customer asks for a human agent, reply exactly HUMAN_HANDOFF_REQUIRED.
            - If the question needs a human decision, reply exactly HUMAN_HANDOFF_REQUIRED.
            - You may use WhatsApp template tools only when the business has an AI-enabled approved template for that use case.
            - Never create template names yourself. Use listAvailableTemplates before sending if unsure.
            - Do not send MARKETING templates unless the customer has clearly opted in or requested promotional information.
            """;

    private final WhatsAppAgent whatsAppAgent;
    private final KnowledgeService knowledgeService;
    private final WhatsAppTemplateService templateService;

    public String reply(TenantEntity tenant, ContactEntity contact, ConversationEntity conversation, String customerPhoneNumber, String userMessage) {
        String ragContext = knowledgeService.buildContext(tenant.getId(), userMessage);
        String templateContext = templateService.describeAiEnabledTemplates(tenant);
        String systemPrompt = tenant.getSystemPrompt()
                + "\n\n" + GLOBAL_GUARDRAILS
                + "\n\nApproved WhatsApp templates:\n" + templateContext
                + "\n\nKnowledge base context:\n" + ragContext;

        try {
            TenantContext.setTenantId(tenant.getId());
            return whatsAppAgent.chat(
                    systemPrompt,
                    contact.getWaId(),
                    userMessage
            );
        } finally {
            TenantContext.clear();
        }
    }
}
