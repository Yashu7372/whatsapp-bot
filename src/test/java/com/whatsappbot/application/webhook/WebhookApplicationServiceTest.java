package com.whatsappbot.application.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.application.ai.TenantAiService;
import com.whatsappbot.application.conversation.ConversationService;
import com.whatsappbot.application.tenant.TenantService;
import com.whatsappbot.domain.contact.ContactEntity;
import com.whatsappbot.domain.conversation.ConversationEntity;
import com.whatsappbot.domain.conversation.ConversationStatus;
import com.whatsappbot.domain.message.MessageType;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.infrastructure.whatsapp.WhatsAppGraphClient;
import com.whatsappbot.infrastructure.whatsapp.WhatsAppInboundMessage;
import com.whatsappbot.infrastructure.whatsapp.WhatsAppWebhookParser;
import com.whatsappbot.lead.LeadSignalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebhookApplicationServiceTest {

    private static final String PHONE_NUMBER_ID = "1234567890";
    private static final String CUSTOMER_WA_ID = "971521000001";
    private static final String CUSTOMER_PHONE = "971521000001";

    @Mock private WhatsAppWebhookParser webhookParser;
    @Mock private TenantService tenantService;
    @Mock private ConversationService conversationService;
    @Mock private TenantAiService tenantAiService;
    @Mock private WhatsAppGraphClient whatsAppGraphClient;
    @Mock private WhatsappInteractiveInboundHandler interactiveInboundHandler;
    @Mock private LeadSignalService leadSignalService;

    @InjectMocks
    private WebhookApplicationService service;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private TenantEntity tenant;
    private ContactEntity contact;
    private ConversationEntity conversation;

    @BeforeEach
    void setUp() {
        tenant = new TenantEntity();
        tenant.setId(UUID.randomUUID());
        tenant.setTenantCode("speedwheels");

        contact = new ContactEntity();
        contact.setId(UUID.randomUUID());
        contact.setWaId(CUSTOMER_WA_ID);
        contact.setPhoneNumber(CUSTOMER_PHONE);

        conversation = ConversationEntity.create(tenant, contact);
        conversation.setId(UUID.randomUUID());
    }

    private WhatsAppInboundMessage inbound(MessageType type, String text) {
        return new WhatsAppInboundMessage(
                PHONE_NUMBER_ID, CUSTOMER_WA_ID, CUSTOMER_PHONE, "Test Customer",
                "wamid.test-1", type, text, "{}", objectMapper.createObjectNode());
    }

    private void stubRegisteredInbound(WhatsAppInboundMessage message) {
        when(webhookParser.parseFirstMessage(any())).thenReturn(Optional.of(message));
        when(tenantService.resolveActiveTenant(PHONE_NUMBER_ID)).thenReturn(tenant);
        when(conversationService.registerInboundMessage(
                eq(tenant), eq(CUSTOMER_WA_ID), eq(CUSTOMER_PHONE), anyString(),
                anyString(), any(), any(), anyString()))
                .thenReturn(new ConversationService.ConversationContext(contact, conversation));
    }

    @Test
    void normalTextMessage_getsAiReply_sentAndPersisted() {
        WhatsAppInboundMessage message = inbound(MessageType.TEXT, "Hi, I need an oil change");
        stubRegisteredInbound(message);
        when(interactiveInboundHandler.handleIfNativeInteractivePayload(any(), any(), any(), any())).thenReturn(false);
        when(tenantAiService.reply(tenant, contact, conversation, CUSTOMER_PHONE, "Hi, I need an oil change"))
                .thenReturn("Sure, when would you like to come in?");

        service.handleIncomingWebhook(objectMapper.createObjectNode());

        verify(whatsAppGraphClient).sendTextMessage(tenant, CUSTOMER_PHONE, "Sure, when would you like to come in?");
        verify(conversationService).saveAiOutbound(tenant, conversation, "Sure, when would you like to come in?");
        verify(conversationService, never()).markHumanRequested(any());
    }

    @Test
    void duplicateMessage_isSkippedEntirely() {
        WhatsAppInboundMessage message = inbound(MessageType.TEXT, "hello again");
        when(webhookParser.parseFirstMessage(any())).thenReturn(Optional.of(message));
        when(tenantService.resolveActiveTenant(PHONE_NUMBER_ID)).thenReturn(tenant);
        when(conversationService.registerInboundMessage(
                any(), anyString(), anyString(), anyString(), anyString(), any(), any(), anyString()))
                .thenReturn(null);

        service.handleIncomingWebhook(objectMapper.createObjectNode());

        verifyNoInteractions(tenantAiService, whatsAppGraphClient, interactiveInboundHandler);
    }

    @Test
    void aiHandoffKeyword_marksHumanRequested_andSendsHandoffReply() {
        WhatsAppInboundMessage message = inbound(MessageType.TEXT, "I want to talk to a human");
        stubRegisteredInbound(message);
        when(interactiveInboundHandler.handleIfNativeInteractivePayload(any(), any(), any(), any())).thenReturn(false);
        when(tenantAiService.reply(any(), any(), any(), anyString(), anyString()))
                .thenReturn("HUMAN_HANDOFF_REQUIRED");

        service.handleIncomingWebhook(objectMapper.createObjectNode());

        verify(conversationService).markHumanRequested(conversation);
        verify(whatsAppGraphClient).sendTextMessage(eq(tenant), eq(CUSTOMER_PHONE), anyString());
        verify(conversationService).saveAiOutbound(eq(tenant), eq(conversation), anyString());
    }

    @Test
    void nonTextMessage_marksHumanRequested_withoutCallingAi() {
        WhatsAppInboundMessage message = inbound(MessageType.IMAGE, null);
        stubRegisteredInbound(message);
        when(interactiveInboundHandler.handleIfNativeInteractivePayload(any(), any(), any(), any())).thenReturn(false);

        service.handleIncomingWebhook(objectMapper.createObjectNode());

        verify(conversationService).markHumanRequested(conversation);
        verify(whatsAppGraphClient).sendTextMessage(eq(tenant), eq(CUSTOMER_PHONE), anyString());
        verifyNoInteractions(tenantAiService);
    }

    @Test
    void botDisabledConversation_doesNotReply() {
        WhatsAppInboundMessage message = inbound(MessageType.TEXT, "hello?");
        stubRegisteredInbound(message);
        when(interactiveInboundHandler.handleIfNativeInteractivePayload(any(), any(), any(), any())).thenReturn(false);
        conversation.setStatus(ConversationStatus.INTERVENE);
        conversation.setBotEnabled(false);

        service.handleIncomingWebhook(objectMapper.createObjectNode());

        verifyNoInteractions(tenantAiService, whatsAppGraphClient);
        verify(conversationService, never()).saveAiOutbound(any(), any(), anyString());
    }

    @Test
    void nativeInteractivePayload_shortCircuitsAiFlow() {
        WhatsAppInboundMessage message = inbound(MessageType.INTERACTIVE, null);
        stubRegisteredInbound(message);
        when(interactiveInboundHandler.handleIfNativeInteractivePayload(any(), any(), any(), any())).thenReturn(true);

        service.handleIncomingWebhook(objectMapper.createObjectNode());

        verifyNoInteractions(tenantAiService, whatsAppGraphClient);
    }
}
