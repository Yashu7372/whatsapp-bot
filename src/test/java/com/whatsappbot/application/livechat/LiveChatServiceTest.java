package com.whatsappbot.application.livechat;

import com.whatsappbot.domain.agent.TenantAgent;
import com.whatsappbot.domain.agent.TenantAgentRepository;
import com.whatsappbot.domain.contact.ContactEntity;
import com.whatsappbot.domain.conversation.ConversationEntity;
import com.whatsappbot.domain.conversation.ConversationRepository;
import com.whatsappbot.domain.conversation.ConversationStatus;
import com.whatsappbot.domain.tenant.TenantEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveChatServiceTest {

    @Mock private ConversationRepository conversationRepository;
    @Mock private TenantAgentRepository tenantAgentRepository;
    @Mock private ConversationEventRepository eventRepository;

    @InjectMocks
    private LiveChatService service;

    private TenantEntity tenant;
    private ConversationEntity conversation;
    private TenantAgent agent;

    @BeforeEach
    void setUp() {
        tenant = new TenantEntity();
        tenant.setId(UUID.randomUUID());

        ContactEntity contact = new ContactEntity();
        contact.setId(UUID.randomUUID());

        conversation = ConversationEntity.create(tenant, contact);
        conversation.setId(UUID.randomUUID());

        agent = new TenantAgent();
        agent.setId(UUID.randomUUID());
        agent.setTenant(tenant);
        agent.setName("Support Agent");
    }

    private void stubLookups() {
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        when(tenantAgentRepository.findByTenantAndIdAndActiveTrue(tenant, agent.getId())).thenReturn(Optional.of(agent));
    }

    @Test
    void intervene_disablesBot_andAssignsAgent() {
        stubLookups();
        conversation.requestHuman();

        ConversationEntity result = service.intervene(tenant, conversation.getId(), agent.getId(), "taking over");

        assertThat(result.getStatus()).isEqualTo(ConversationStatus.INTERVENE);
        assertThat(result.isBotEnabled()).isFalse();
        assertThat(result.getAssignedAgentId()).isEqualTo(agent.getId());
        assertThat(result.canBotReply()).isFalse();

        ArgumentCaptor<ConversationEvent> event = ArgumentCaptor.forClass(ConversationEvent.class);
        verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(ConversationEventType.BOT_DISABLED);
        assertThat(event.getValue().getToAgentId()).isEqualTo(agent.getId());
    }

    @Test
    void assign_setsAssignedAgent_andWritesAuditEvent() {
        stubLookups();

        ConversationEntity result = service.assign(tenant, conversation.getId(), agent.getId(), null);

        assertThat(result.getAssignedAgentId()).isEqualTo(agent.getId());
        ArgumentCaptor<ConversationEvent> event = ArgumentCaptor.forClass(ConversationEvent.class);
        verify(eventRepository).save(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo(ConversationEventType.AGENT_ASSIGNED);
    }

    @Test
    void resolve_reEnablesBot_andMarksResolved() {
        stubLookups();
        conversation.setStatus(ConversationStatus.INTERVENE);
        conversation.setBotEnabled(false);

        ConversationEntity result = service.resolve(tenant, conversation.getId(), agent.getId(), "done");

        assertThat(result.getStatus()).isEqualTo(ConversationStatus.RESOLVED);
        assertThat(result.isBotEnabled()).isTrue();
    }

    @Test
    void reopenForBot_clearsAssignedAgent_andActivates() {
        stubLookups();
        conversation.setStatus(ConversationStatus.RESOLVED);
        conversation.setAssignedAgentId(agent.getId());

        ConversationEntity result = service.reopenForBot(tenant, conversation.getId(), agent.getId(), null);

        assertThat(result.getStatus()).isEqualTo(ConversationStatus.ACTIVE);
        assertThat(result.isBotEnabled()).isTrue();
        assertThat(result.getAssignedAgentId()).isNull();
        assertThat(result.canBotReply()).isTrue();
    }

    @Test
    void conversationOfAnotherTenant_isRejected() {
        TenantEntity otherTenant = new TenantEntity();
        otherTenant.setId(UUID.randomUUID());
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));

        assertThatThrownBy(() -> service.assign(otherTenant, conversation.getId(), agent.getId(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Conversation not found");
    }

    @Test
    void unknownAgent_isRejected() {
        when(conversationRepository.findById(conversation.getId())).thenReturn(Optional.of(conversation));
        when(tenantAgentRepository.findByTenantAndIdAndActiveTrue(any(), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assign(tenant, conversation.getId(), UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Agent not found");
    }
}
