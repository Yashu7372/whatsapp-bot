package com.whatsappbot.application.livechat;

import com.whatsappbot.domain.agent.TenantAgent;
import com.whatsappbot.domain.agent.TenantAgentRepository;
import com.whatsappbot.domain.conversation.ConversationEntity;
import com.whatsappbot.domain.conversation.ConversationRepository;
import com.whatsappbot.domain.conversation.ConversationStatus;
import com.whatsappbot.domain.tenant.TenantEntity;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class LiveChatService {

    private final ConversationRepository conversationRepository;
    private final TenantAgentRepository tenantAgentRepository;
    private final ConversationEventRepository eventRepository;

    @Transactional
    public ConversationEntity intervene(TenantEntity tenant, UUID conversationId, UUID agentId, String notes) {
        ConversationEntity conversation = getConversation(tenant, conversationId);
        TenantAgent agent = getAgent(tenant, agentId);
        conversation.setStatus(ConversationStatus.INTERVENE);
        conversation.setBotEnabled(false);
        conversation.setAssignedAgentId(agent.getId());
        conversation.touchLastMessageAt();
        conversationRepository.save(conversation);
        eventRepository.save(ConversationEvent.create(tenant, conversation, ConversationEventType.BOT_DISABLED, null, agent.getId(), notes));
        return conversation;
    }

    @Transactional
    public ConversationEntity assign(TenantEntity tenant, UUID conversationId, UUID agentId, String notes) {
        ConversationEntity conversation = getConversation(tenant, conversationId);
        TenantAgent agent = getAgent(tenant, agentId);
        conversation.setAssignedAgentId(agent.getId());
        conversationRepository.save(conversation);
        eventRepository.save(ConversationEvent.create(tenant, conversation, ConversationEventType.AGENT_ASSIGNED, null, agent.getId(), notes));
        return conversation;
    }

    @Transactional
    public ConversationEntity transfer(TenantEntity tenant, UUID conversationId, UUID fromAgentId, UUID toAgentId, String notes) {
        ConversationEntity conversation = getConversation(tenant, conversationId);
        getAgent(tenant, fromAgentId);
        TenantAgent toAgent = getAgent(tenant, toAgentId);
        conversation.setAssignedAgentId(toAgent.getId());
        conversationRepository.save(conversation);
        eventRepository.save(ConversationEvent.create(tenant, conversation, ConversationEventType.CHAT_TRANSFERRED, fromAgentId, toAgentId, notes));
        return conversation;
    }

    @Transactional
    public ConversationEntity resolve(TenantEntity tenant, UUID conversationId, UUID agentId, String notes) {
        ConversationEntity conversation = getConversation(tenant, conversationId);
        getAgent(tenant, agentId);
        conversation.setStatus(ConversationStatus.RESOLVED);
        conversation.setBotEnabled(true);
        conversationRepository.save(conversation);
        eventRepository.save(ConversationEvent.create(tenant, conversation, ConversationEventType.RESOLVED, agentId, null, notes));
        return conversation;
    }

    @Transactional
    public ConversationEntity reopenForBot(TenantEntity tenant, UUID conversationId, UUID agentId, String notes) {
        ConversationEntity conversation = getConversation(tenant, conversationId);
        getAgent(tenant, agentId);
        conversation.setStatus(ConversationStatus.ACTIVE);
        conversation.setBotEnabled(true);
        conversation.setAssignedAgentId(null);
        conversationRepository.save(conversation);
        eventRepository.save(ConversationEvent.create(tenant, conversation, ConversationEventType.BOT_ENABLED, agentId, null, notes));
        return conversation;
    }

    private ConversationEntity getConversation(TenantEntity tenant, UUID conversationId) {
        return conversationRepository.findById(conversationId)
                .filter(c -> c.getTenant().getId().equals(tenant.getId()))
                .orElseThrow(() -> new IllegalArgumentException("Conversation not found for tenant: " + conversationId));
    }

    private TenantAgent getAgent(TenantEntity tenant, UUID agentId) {
        return tenantAgentRepository.findByTenantAndIdAndActiveTrue(tenant, agentId)
                .orElseThrow(() -> new IllegalArgumentException("Agent not found for tenant: " + agentId));
    }
}
