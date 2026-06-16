package com.whatsappbot.application.conversation;

import com.whatsappbot.domain.contact.ContactEntity;
import com.whatsappbot.domain.contact.ContactRepository;
import com.whatsappbot.domain.conversation.ConversationEntity;
import com.whatsappbot.domain.conversation.ConversationRepository;
import com.whatsappbot.domain.message.Message;
import com.whatsappbot.domain.message.MessageRepository;
import com.whatsappbot.domain.message.MessageType;
import com.whatsappbot.domain.tenant.TenantEntity;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConversationService {

    private final ContactRepository contactRepository;
    private final ConversationRepository conversationRepository;
    private final MessageRepository messageRepository;

    @Transactional
    public ConversationContext registerInboundMessage(TenantEntity tenant,
                                                      String waId,
                                                      String phoneNumber,
                                                      String displayName,
                                                      String waMessageId,
                                                      MessageType messageType,
                                                      String textBody,
                                                      String rawPayload) {

        if (waMessageId != null && !waMessageId.isBlank() && messageRepository.existsByWaMessageId(waMessageId)) {
            log.debug("Duplicate inbound WhatsApp message skipped before processing. waMessageId={}", waMessageId);
            return null;
        }

        // 1. Fetch or create the contact
        ContactEntity contact = contactRepository.findByTenantAndWaId(tenant, waId)
                .orElseGet(() -> ContactEntity.create(tenant, waId, phoneNumber, displayName));

        contact.setDisplayName(displayName != null && !displayName.isBlank() ? displayName : contact.getDisplayName());
        contact.markSeenNow();

        // 2. Save and assign to a NEW variable to keep it effectively final
        ContactEntity savedContact = contactRepository.save(contact);

        // 3. Use the new 'savedContact' variable in your query and lambda
        ConversationEntity conversation = conversationRepository.findByTenantAndContact(tenant, savedContact)
                .orElseGet(() -> ConversationEntity.create(tenant, savedContact));

        conversation.touchLastMessageAt();

        // 4. Assign the saved conversation to a new variable as well,
        // to prevent the same issue if you add lambdas further down
        ConversationEntity savedConversation = conversationRepository.save(conversation);

        // 5. Use the saved entities to create the message
        Message inbound = Message.inbound(tenant, savedConversation, waMessageId, messageType, textBody, rawPayload);
        messageRepository.saveAndFlush(inbound);

        return new ConversationContext(savedContact, savedConversation);
    }

    @Transactional
    public void saveAiOutbound(TenantEntity tenant, ConversationEntity conversation, String responseText) {
        conversation.touchLastMessageAt();
        conversationRepository.save(conversation);
        messageRepository.save(Message.outboundAi(tenant, conversation, MessageType.TEXT, responseText));
    }

    @Transactional
    public void markHumanRequested(ConversationEntity conversation) {
        conversation.requestHuman();
        conversationRepository.save(conversation);
    }

    public record ConversationContext(ContactEntity contactEntity, ConversationEntity conversationEntity) {
    }
}
