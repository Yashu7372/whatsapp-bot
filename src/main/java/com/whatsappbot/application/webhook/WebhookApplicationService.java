package com.whatsappbot.application.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.whatsappbot.application.ai.TenantAiService;
import com.whatsappbot.application.conversation.ConversationService;
import com.whatsappbot.application.tenant.TenantService;
import com.whatsappbot.domain.conversation.ConversationEntity;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.infrastructure.whatsapp.WhatsAppGraphClient;
import com.whatsappbot.infrastructure.whatsapp.WhatsAppInboundMessage;
import com.whatsappbot.infrastructure.whatsapp.WhatsAppWebhookParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookApplicationService {

    private static final String HUMAN_HANDOFF_REQUIRED = "HUMAN_HANDOFF_REQUIRED";
    private static final String HUMAN_HANDOFF_REPLY = "Thanks. I have shared this with our team. A human agent will assist you shortly.";
    private static final String NON_TEXT_REPLY = "Thanks. I received your message. Please send your request as text, or our team will assist you shortly.";

    private final WhatsAppWebhookParser webhookParser;
    private final TenantService tenantService;
    private final ConversationService conversationService;
    private final TenantAiService tenantAiService;
    private final WhatsAppGraphClient whatsAppGraphClient;
    private final WhatsappInteractiveInboundHandler interactiveInboundHandler;

    public void handleIncomingWebhook(JsonNode payload) {
        webhookParser.parseFirstMessage(payload).ifPresent(this::handleMessage);
    }


    private void handleMessage(WhatsAppInboundMessage inbound) {
        TenantEntity tenant = tenantService.resolveActiveTenant(inbound.phoneNumberId());
        ConversationService.ConversationContext context = conversationService.registerInboundMessage(
                tenant,
                inbound.fromWaId(),
                inbound.fromPhoneNumber(),
                inbound.displayName(),
                inbound.waMessageId(),
                inbound.messageType(),
                inbound.textBody(),
                inbound.rawPayload()
        );

        ConversationEntity conversation = context.conversationEntity();
        log.info("Inbound WhatsApp message. tenant={}, conversation={}, type={}",
                tenant.getTenantCode(), conversation.getId(), inbound.messageType());
        boolean handledNativePayload = interactiveInboundHandler.handleIfNativeInteractivePayload(
                tenant,
                context.contactEntity(),
                conversation,
                inbound.messageNode()
        );

        if (handledNativePayload) {
            log.info("Native WhatsApp payload handled. tenant={}, conversation={}, type={}",
                    tenant.getTenantCode(), conversation.getId(), inbound.messageType());

            // For cart/order/list/button/flow replies, we stop normal AI flow here.
            // Later you can route based on button id, list id, flow response, or order payload.
            return;
        }

        if (!conversation.canBotReply()) {
            log.info("Bot reply skipped. tenant={}, conversation={}, status={}",
                    tenant.getTenantCode(), conversation.getId(), conversation.getStatus());
            return;
        }

        if (inbound.textBody() == null || inbound.textBody().isBlank()) {
            conversationService.markHumanRequested(conversation);
            whatsAppGraphClient.sendTextMessage(tenant, inbound.fromPhoneNumber(), NON_TEXT_REPLY);
            conversationService.saveAiOutbound(tenant, conversation, NON_TEXT_REPLY);
            return;
        }

        String aiResponse = tenantAiService.reply(
                tenant,
                context.contactEntity(),
                conversation,
                inbound.fromPhoneNumber(),
                inbound.textBody()
        );
        if (HUMAN_HANDOFF_REQUIRED.equalsIgnoreCase(aiResponse)) {
            conversationService.markHumanRequested(conversation);
            whatsAppGraphClient.sendTextMessage(tenant, inbound.fromPhoneNumber(), HUMAN_HANDOFF_REPLY);
            conversationService.saveAiOutbound(tenant, conversation, HUMAN_HANDOFF_REPLY);
            return;
        }

        whatsAppGraphClient.sendTextMessage(tenant, inbound.fromPhoneNumber(), aiResponse);
        conversationService.saveAiOutbound(tenant, conversation, aiResponse);
    }
}
