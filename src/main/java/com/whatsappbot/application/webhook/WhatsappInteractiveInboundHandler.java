package com.whatsappbot.application.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.whatsappbot.application.conversation.ConversationService;
import com.whatsappbot.domain.contact.ContactEntity;
import com.whatsappbot.domain.conversation.ConversationEntity;
import com.whatsappbot.domain.interactive.WhatsappButtonReplyEntity;
import com.whatsappbot.domain.interactive.WhatsappButtonReplyRepository;
import com.whatsappbot.domain.order.WhatsappOrderEntity;
import com.whatsappbot.domain.order.WhatsappOrderRepository;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.infrastructure.whatsapp.WhatsAppGraphClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * Handles WhatsApp-native interactive payloads (button replies, list replies,
 * orders, location shares) that arrive as structured JSON rather than free
 * text, so they never get routed through the AI chat flow.
 * <p>
 * Button/list reply routing is fully data-driven via
 * {@link WhatsappButtonReplyRepository} — every tenant's buttons (whatever
 * business type they are) are rows in {@code whatsapp_button_replies}, not
 * Java branches. Onboarding a new tenant's quick-reply buttons is a data
 * insert; this class never changes per business.
 * <p>
 * Per resilient-error-handling: an unrecognized id is not a failure, it's
 * simply acknowledged and swallowed rather than guessed at, exactly like the
 * existing {@code NO_CUSTOMER_FOUND}-style sentinel pattern used elsewhere.
 */
@Slf4j
@Service
public class WhatsappInteractiveInboundHandler {

    private static final String REPLY_KIND_TEXT = "TEXT";

    private final WhatsappOrderRepository orderRepository;
    private final WhatsAppGraphClient whatsAppGraphClient;
    private final ConversationService conversationService;
    private final WhatsappButtonReplyRepository buttonReplyRepository;

    public WhatsappInteractiveInboundHandler(
            WhatsappOrderRepository orderRepository,
            WhatsAppGraphClient whatsAppGraphClient,
            ConversationService conversationService,
            WhatsappButtonReplyRepository buttonReplyRepository
    ) {
        this.orderRepository = orderRepository;
        this.whatsAppGraphClient = whatsAppGraphClient;
        this.conversationService = conversationService;
        this.buttonReplyRepository = buttonReplyRepository;
    }

    @Transactional
    public boolean handleIfNativeInteractivePayload(
            TenantEntity tenant,
            ContactEntity contact,
            ConversationEntity conversation,
            JsonNode messageNode
    ) {
        String type = messageNode.path("type").asText();

        if ("order".equals(type)) {
            WhatsappOrderEntity order = new WhatsappOrderEntity();
            order.setTenant(tenant);
            order.setContact(contact);
            order.setConversation(conversation);
            order.setWaMessageId(messageNode.path("id").asText(null));
            order.setCatalogId(messageNode.path("order").path("catalog_id").asText(null));
            order.setOrderPayload(messageNode.path("order").toString());
            orderRepository.save(order);
            return true;
        }

        if ("interactive".equals(type)) {
            return handleInteractiveReply(tenant, contact, conversation, messageNode);
        }

        if ("location".equals(type)) {
            // Store customer shared location and attach it to the active conversation/order lead.
            return true;
        }

        return false;
    }

    private boolean handleInteractiveReply(
            TenantEntity tenant,
            ContactEntity contact,
            ConversationEntity conversation,
            JsonNode messageNode
    ) {
        JsonNode interactive = messageNode.path("interactive");
        String interactiveType = interactive.path("type").asText();

        String replyId = switch (interactiveType) {
            case "button_reply" -> interactive.path("button_reply").path("id").asText(null);
            case "list_reply" -> interactive.path("list_reply").path("id").asText(null);
            // nfm_reply (Flow responses) and any other interactive subtype: not yet
            // routed to a specific handler. Acknowledge and stop here rather than
            // guess — surfaced via the log line below for visibility.
            default -> null;
        };

        if (replyId == null) {
            log.info("Unhandled interactive subtype, payload acknowledged only. tenant={}, conversation={}, interactiveType={}",
                    tenant.getTenantCode(), conversation.getId(), interactiveType);
            return true;
        }

        Optional<WhatsappButtonReplyEntity> registered =
                buttonReplyRepository.findByTenantAndButtonIdAndActiveTrue(tenant, replyId);

        if (registered.isEmpty()) {
            log.info("Unrecognized button/list reply id, payload acknowledged only. tenant={}, conversation={}, replyId={}",
                    tenant.getTenantCode(), conversation.getId(), replyId);
            return true;
        }

        WhatsappButtonReplyEntity entry = registered.get();

        if (!REPLY_KIND_TEXT.equals(entry.getReplyKind())) {
            // TOOL_CALL (or any future kind) is registered in the schema but not
            // yet implemented here. Acknowledge rather than silently mis-route.
            log.warn("Button reply registered with unimplemented kind, payload acknowledged only. "
                            + "tenant={}, conversation={}, replyId={}, replyKind={}",
                    tenant.getTenantCode(), conversation.getId(), replyId, entry.getReplyKind());
            return true;
        }

        whatsAppGraphClient.sendTextMessage(tenant, contact.getPhoneNumber(), entry.getReplyText());
        conversationService.saveAiOutbound(tenant, conversation, entry.getReplyText());
        log.info("Routed button reply to registered response. tenant={}, conversation={}, replyId={}",
                tenant.getTenantCode(), conversation.getId(), replyId);
        return true;
    }
}