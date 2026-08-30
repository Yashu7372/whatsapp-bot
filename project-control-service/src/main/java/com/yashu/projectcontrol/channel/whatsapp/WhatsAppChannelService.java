package com.yashu.projectcontrol.channel.whatsapp;

import com.yashu.projectcontrol.access.IdentityService;
import com.yashu.projectcontrol.assistant.ProjectControlAssistantService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

@Service
public class WhatsAppChannelService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppChannelService.class);
    private static final int SCAN_BATCH_SIZE = 50;

    private final WhatsAppChannelProperties properties;
    private final WhatsAppChannelRepository repository;
    private final IdentityService identityService;
    private final ProjectControlAssistantService assistantService;
    private final WhatsAppGraphClient graphClient;

    public WhatsAppChannelService(
            WhatsAppChannelProperties properties,
            WhatsAppChannelRepository repository,
            IdentityService identityService,
            ProjectControlAssistantService assistantService,
            WhatsAppGraphClient graphClient) {
        this.properties = properties;
        this.repository = repository;
        this.identityService = identityService;
        this.assistantService = assistantService;
        this.graphClient = graphClient;
    }

    @Scheduled(
            fixedDelayString = "${project-control.whatsapp.notification-scan-ms:5000}",
            initialDelayString = "${project-control.whatsapp.notification-initial-delay-ms:5000}")
    public void sendAssignedReviewerBriefs() {
        if (!properties.enabled()) return;
        ensureConfiguredIdentity();
        for (var pending : repository.pendingSteps(SCAN_BATCH_SIZE)) {
            try {
                var brief = assistantService.reviewerBrief(pending.userId(), pending.workflowInstanceId());
                String message = "Project Control review\n\n"
                        + brief.summary()
                        + "\n\nReply to this message with a question about this review. "
                        + "Approval, rejection and certification remain authorized Project Control actions in the application.";
                graphClient.sendText(pending.externalAddress(), message);
                repository.markNotification(pending, "SENT", true);
                repository.setActiveContext(pending.channelIdentityId(), pending.workflowInstanceId());
                log.info("Sent Project Control reviewer brief for workflow {} step {} to channel identity {}",
                        pending.workflowInstanceId(), pending.workflowStepInstanceId(), pending.channelIdentityId());
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode().value() == HttpStatus.FORBIDDEN.value()) {
                    repository.markNotification(pending, "SKIPPED_NOT_ASSIGNED", false);
                } else if (ex.getStatusCode().value() == HttpStatus.BAD_REQUEST.value()
                        || ex.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
                    repository.markNotification(pending, "SKIPPED_NOT_APPLICABLE", false);
                } else {
                    log.warn("Could not prepare reviewer brief for workflow {}: {}",
                            pending.workflowInstanceId(), ex.getReason());
                }
            } catch (RuntimeException ex) {
                // Do not mark as sent. The next scan can retry after a transient Meta/provider failure.
                log.warn("Could not deliver Project Control WhatsApp notification for workflow {}",
                        pending.workflowInstanceId(), ex);
            }
        }
    }

    public void handleInboundText(String sender, String providerMessageId, String text) {
        if (!properties.enabled()) return;
        ensureConfiguredIdentity();
        String address = WhatsAppChannelProperties.normalizeAddress(sender);
        Optional<WhatsAppChannelRepository.ChannelIdentity> identityOptional = repository.findIdentity(address);
        if (identityOptional.isEmpty()) {
            log.warn("Ignoring WhatsApp message from an address that is not bound to a Project Control user");
            return;
        }
        var identity = identityOptional.get();
        var claimed = repository.claimInbound(identity.id(), providerMessageId, text);
        if (claimed.isEmpty()) return;

        try {
            Optional<java.util.UUID> workflowId = repository.activeWorkflow(identity.id());
            if (workflowId.isEmpty()) {
                graphClient.sendText(identity.externalAddress(),
                        "No active Project Control review is currently linked to this WhatsApp identity. "
                                + "When work is assigned to you, the reviewer brief will establish the active context.");
                repository.markInboundProcessed(claimed.get().id());
                return;
            }

            try {
                var answer = assistantService.answer(identity.userId(), workflowId.get(), text);
                graphClient.sendText(identity.externalAddress(), answer.answer());
            } catch (ResponseStatusException ex) {
                if (ex.getStatusCode().value() == HttpStatus.FORBIDDEN.value()
                        || ex.getStatusCode().value() == HttpStatus.CONFLICT.value()) {
                    graphClient.sendText(identity.externalAddress(),
                            "That review is no longer assigned to you or is no longer active. "
                                    + "Use the Project Control worklist for the current assignment.");
                } else {
                    throw ex;
                }
            }
            repository.markInboundProcessed(claimed.get().id());
        } catch (RuntimeException ex) {
            log.warn("Could not process inbound Project Control WhatsApp message {}",
                    providerMessageId, ex);
            throw ex;
        }
    }

    private void ensureConfiguredIdentity() {
        if (!properties.hasLocalIdentityBinding()) return;
        try {
            var user = identityService.getUserByEmail(properties.localUserEmail());
            repository.ensureIdentity(user.id(), properties.localUserNumber());
        } catch (ResponseStatusException ex) {
            log.debug("Configured WhatsApp user is not available yet: {}", properties.localUserEmail());
        }
    }
}
