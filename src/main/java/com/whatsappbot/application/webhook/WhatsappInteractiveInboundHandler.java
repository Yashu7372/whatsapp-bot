package com.whatsappbot.application.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.whatsappbot.domain.contact.ContactEntity;
import com.whatsappbot.domain.conversation.ConversationEntity;
import com.whatsappbot.domain.order.WhatsappOrderEntity;
import com.whatsappbot.domain.order.WhatsappOrderRepository;
import com.whatsappbot.domain.tenant.TenantEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WhatsappInteractiveInboundHandler {

    private final WhatsappOrderRepository orderRepository;

    public WhatsappInteractiveInboundHandler(WhatsappOrderRepository orderRepository) {
        this.orderRepository = orderRepository;
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
            // Examples:
            // interactive.button_reply.id/title
            // interactive.list_reply.id/title
            // interactive.nfm_reply.response_json for Flows
            // Route these to your conversation/message tables and business workflow.
            return true;
        }

        if ("location".equals(type)) {
            // Store customer shared location and attach it to the active conversation/order lead.
            return true;
        }

        return false;
    }
}
