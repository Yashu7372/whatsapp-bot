package com.whatsappbot.application.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.whatsappbot.application.ai.TenantAiService;
import com.whatsappbot.application.conversation.ConversationService;
import com.whatsappbot.application.tenant.TenantService;
import com.whatsappbot.document.DocumentEntity;
import com.whatsappbot.document.IntakeChannel;
import com.whatsappbot.document.intake.DocumentIntakeProperties;
import com.whatsappbot.document.intake.DocumentIntakeService;
import com.whatsappbot.document.intake.FileTooLargeException;
import com.whatsappbot.document.intake.MalwareDetectedException;
import com.whatsappbot.document.intake.ScannerUnavailableException;
import com.whatsappbot.domain.conversation.ConversationEntity;
import com.whatsappbot.domain.message.MessageType;
import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.features.FeatureAccessService;
import com.whatsappbot.features.FeatureCode;
import com.whatsappbot.infrastructure.whatsapp.WhatsAppGraphClient;
import com.whatsappbot.infrastructure.whatsapp.WhatsAppInboundMessage;
import com.whatsappbot.infrastructure.whatsapp.WhatsAppWebhookParser;
import com.whatsappbot.lead.LeadSignalService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class WebhookApplicationService {

    private static final String HUMAN_HANDOFF_REQUIRED = "HUMAN_HANDOFF_REQUIRED";
    private static final String HUMAN_HANDOFF_REPLY = "Thanks. I have shared this with our team. A human agent will assist you shortly.";
    private static final String NON_TEXT_REPLY = "Thanks. I received your message. Please send your request as text, or our team will assist you shortly.";
    private static final String DOCUMENT_RECEIVED_REPLY = "Thanks, we've received your document. Our team will review it shortly.";
    private static final String DOCUMENT_REJECTED_REPLY = "Sorry, we couldn't accept that file — it failed our security scan. Please try a different file or contact our team.";
    private static final String DOCUMENT_UNAVAILABLE_REPLY = "Sorry, we're temporarily unable to process file uploads. Please try again shortly or contact our team.";

    private final WhatsAppWebhookParser webhookParser;
    private final TenantService tenantService;
    private final ConversationService conversationService;
    private final TenantAiService tenantAiService;
    private final WhatsAppGraphClient whatsAppGraphClient;
    private final WhatsappInteractiveInboundHandler interactiveInboundHandler;
    private final LeadSignalService leadSignalService;
    private final DocumentIntakeService documentIntakeService;
    private final DocumentIntakeProperties documentIntakeProperties;
    private final FeatureAccessService featureAccessService;

    public void handleIncomingWebhook(JsonNode payload) {
        webhookParser.parseFirstMessage(payload).ifPresent(this::handleMessage);
    }

    private void handleMessage(WhatsAppInboundMessage inbound) {
        TenantEntity tenant = tenantService.resolveActiveTenant(inbound.phoneNumberId());
        ConversationService.ConversationContext context;
        try {
            context = conversationService.registerInboundMessage(
                    tenant,
                    inbound.fromWaId(),
                    inbound.fromPhoneNumber(),
                    inbound.displayName(),
                    inbound.waMessageId(),
                    inbound.messageType(),
                    inbound.textBody(),
                    inbound.rawPayload()
            );
        } catch (DataIntegrityViolationException ex) {
            log.debug("Duplicate webhook message skipped by database constraint. waMessageId={}", inbound.waMessageId());
            return;
        }

        if (context == null) {
            log.debug("Duplicate webhook message skipped. waMessageId={}", inbound.waMessageId());
            return;
        }

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
            return;
        }

        if (!conversation.canBotReply()) {
            log.info("Bot reply skipped. tenant={}, conversation={}, status={}",
                    tenant.getTenantCode(), conversation.getId(), conversation.getStatus());
            return;
        }

        if (inbound.messageType() == MessageType.DOCUMENT) {
            handleInboundDocument(tenant, context, conversation, inbound);
            return;
        }

        if (inbound.textBody() == null || inbound.textBody().isBlank()) {
            conversationService.markHumanRequested(conversation);
            whatsAppGraphClient.sendTextMessage(tenant, inbound.fromPhoneNumber(), NON_TEXT_REPLY);
            conversationService.saveAiOutbound(tenant, conversation, NON_TEXT_REPLY);
            return;
        }

        try {
            leadSignalService.extractFromInbound(
                    tenant,
                    context.contactEntity().getId(),
                    conversation.getId(),
                    inbound.textBody()
            );
        } catch (Exception e) {
            log.warn("Lead signal extraction failed — non-critical. conversation={}", conversation.getId(), e);
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

    private void handleInboundDocument(TenantEntity tenant, ConversationService.ConversationContext context,
                                       ConversationEntity conversation, WhatsAppInboundMessage inbound) {
        if (!featureAccessService.isFeatureEnabled(tenant.getId(), FeatureCode.DOCUMENT_CONTROL)) {
            conversationService.markHumanRequested(conversation);
            whatsAppGraphClient.sendTextMessage(tenant, inbound.fromPhoneNumber(), NON_TEXT_REPLY);
            conversationService.saveAiOutbound(tenant, conversation, NON_TEXT_REPLY);
            return;
        }

        JsonNode documentNode = inbound.messageNode().path("document");
        String mediaId = documentNode.path("id").asText(null);
        String filename = documentNode.path("filename").asText("document");
        String caption = documentNode.path("caption").asText(null);

        String reply;
        if (mediaId == null) {
            log.warn("WhatsApp document message with no media id. tenant={} conversation={}",
                    tenant.getTenantCode(), conversation.getId());
            reply = DOCUMENT_UNAVAILABLE_REPLY;
        } else {
            reply = ingestInboundDocument(tenant, context, conversation, mediaId, filename, caption, documentNode);
        }

        conversationService.markHumanRequested(conversation);
        whatsAppGraphClient.sendTextMessage(tenant, inbound.fromPhoneNumber(), reply);
        conversationService.saveAiOutbound(tenant, conversation, reply);
    }

    private String ingestInboundDocument(TenantEntity tenant, ConversationService.ConversationContext context,
                                         ConversationEntity conversation, String mediaId, String filename,
                                         String caption, JsonNode documentNode) {
        try (WhatsAppGraphClient.MediaDownload media = whatsAppGraphClient.downloadMedia(tenant, mediaId)) {
            String contentType = media.contentType() != null
                    ? media.contentType() : documentNode.path("mime_type").asText("application/octet-stream");

            DocumentIntakeService.IntakeRequest request = new DocumentIntakeService.IntakeRequest(
                    tenant.getId(), IntakeChannel.WHATSAPP, documentIntakeProperties.getWhatsappDocType(), null,
                    filename, caption, context.contactEntity().getDisplayName(), null, null);

            DocumentEntity doc = documentIntakeService.ingest(request, filename, contentType, media.stream());
            log.info("WhatsApp document ingested. tenant={} conversation={} documentId={}",
                    tenant.getTenantCode(), conversation.getId(), doc.getId());
            return DOCUMENT_RECEIVED_REPLY;
        } catch (MalwareDetectedException e) {
            log.warn("WhatsApp document rejected by malware scan. tenant={} conversation={}",
                    tenant.getTenantCode(), conversation.getId());
            return DOCUMENT_REJECTED_REPLY;
        } catch (ScannerUnavailableException | FileTooLargeException e) {
            log.warn("WhatsApp document intake could not complete. tenant={} conversation={} reason={}",
                    tenant.getTenantCode(), conversation.getId(), e.getMessage());
            return DOCUMENT_UNAVAILABLE_REPLY;
        } catch (Exception e) {
            log.error("WhatsApp document intake failed unexpectedly. tenant={} conversation={}",
                    tenant.getTenantCode(), conversation.getId(), e);
            return DOCUMENT_UNAVAILABLE_REPLY;
        }
    }
}
