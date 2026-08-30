package com.yashu.projectcontrol.channel.whatsapp;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class WhatsAppChannelRepository {

    private final JdbcClient jdbc;

    public WhatsAppChannelRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional
    public ChannelIdentity ensureIdentity(UUID userId, String externalAddress) {
        String address = WhatsAppChannelProperties.normalizeAddress(externalAddress);
        if (address.isBlank()) throw new IllegalArgumentException("WhatsApp external address is required");
        Optional<ChannelIdentity> existing = findIdentity(address);
        if (existing.isPresent()) {
            ChannelIdentity identity = existing.get();
            if (!identity.userId().equals(userId)) {
                throw new IllegalStateException("WhatsApp number is already bound to another Project Control user");
            }
            return identity;
        }
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        try {
            jdbc.sql("""
                    INSERT INTO channel_identities
                        (id, user_id, channel_type, external_address, status, created_at, updated_at)
                    VALUES (:id, :userId, 'WHATSAPP', :address, 'ACTIVE', :createdAt, :updatedAt)
                    """)
                    .param("id", id)
                    .param("userId", userId)
                    .param("address", address)
                    .param("createdAt", Timestamp.from(now))
                    .param("updatedAt", Timestamp.from(now))
                    .update();
            return new ChannelIdentity(id, userId, address, "ACTIVE");
        } catch (DuplicateKeyException ex) {
            return findIdentity(address).orElseThrow(() -> ex);
        }
    }

    @Transactional(readOnly = true)
    public Optional<ChannelIdentity> findIdentity(String externalAddress) {
        String address = WhatsAppChannelProperties.normalizeAddress(externalAddress);
        if (address.isBlank()) return Optional.empty();
        return jdbc.sql("""
                SELECT id, user_id, external_address, status
                FROM channel_identities
                WHERE channel_type = 'WHATSAPP' AND external_address = :address
                """)
                .param("address", address)
                .query((rs, rowNum) -> new ChannelIdentity(
                        rs.getObject("id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("external_address"),
                        rs.getString("status")))
                .optional();
    }

    @Transactional(readOnly = true)
    public List<PendingStep> pendingSteps(int limit) {
        return jdbc.sql("""
                SELECT ci.id AS channel_identity_id,
                       ci.user_id,
                       ci.external_address,
                       wi.id AS workflow_instance_id,
                       wsi.id AS workflow_step_instance_id
                FROM channel_identities ci
                CROSS JOIN workflow_instances wi
                JOIN workflow_step_instances wsi ON wsi.id = wi.current_step_instance_id
                LEFT JOIN channel_notifications n
                  ON n.channel_identity_id = ci.id
                 AND n.workflow_step_instance_id = wsi.id
                WHERE ci.channel_type = 'WHATSAPP'
                  AND ci.status = 'ACTIVE'
                  AND wi.status = 'RUNNING'
                  AND n.id IS NULL
                ORDER BY wsi.activated_at ASC
                LIMIT :limit
                """)
                .param("limit", limit)
                .query((rs, rowNum) -> new PendingStep(
                        rs.getObject("channel_identity_id", UUID.class),
                        rs.getObject("user_id", UUID.class),
                        rs.getString("external_address"),
                        rs.getObject("workflow_instance_id", UUID.class),
                        rs.getObject("workflow_step_instance_id", UUID.class)))
                .list();
    }

    @Transactional
    public void markNotification(PendingStep step, String status, boolean sent) {
        try {
            jdbc.sql("""
                    INSERT INTO channel_notifications
                        (id, channel_identity_id, workflow_step_instance_id, workflow_instance_id,
                         status, created_at, sent_at)
                    VALUES (:id, :identityId, :stepId, :workflowId, :status, :createdAt, :sentAt)
                    """)
                    .param("id", UUID.randomUUID())
                    .param("identityId", step.channelIdentityId())
                    .param("stepId", step.workflowStepInstanceId())
                    .param("workflowId", step.workflowInstanceId())
                    .param("status", status)
                    .param("createdAt", Timestamp.from(Instant.now()))
                    .param("sentAt", sent ? Timestamp.from(Instant.now()) : null)
                    .update();
        } catch (DuplicateKeyException ignored) {
            // Another scan already recorded this identity/step pair.
        }
    }

    @Transactional
    public void setActiveContext(UUID channelIdentityId, UUID workflowInstanceId) {
        int updated = jdbc.sql("""
                UPDATE channel_contexts
                   SET active_workflow_instance_id = :workflowId, updated_at = :updatedAt
                 WHERE channel_identity_id = :identityId
                """)
                .param("workflowId", workflowInstanceId)
                .param("updatedAt", Timestamp.from(Instant.now()))
                .param("identityId", channelIdentityId)
                .update();
        if (updated == 0) {
            try {
                jdbc.sql("""
                        INSERT INTO channel_contexts
                            (channel_identity_id, active_workflow_instance_id, updated_at)
                        VALUES (:identityId, :workflowId, :updatedAt)
                        """)
                        .param("identityId", channelIdentityId)
                        .param("workflowId", workflowInstanceId)
                        .param("updatedAt", Timestamp.from(Instant.now()))
                        .update();
            } catch (DuplicateKeyException ex) {
                setActiveContext(channelIdentityId, workflowInstanceId);
            }
        }
    }

    @Transactional(readOnly = true)
    public Optional<UUID> activeWorkflow(UUID channelIdentityId) {
        return jdbc.sql("""
                SELECT active_workflow_instance_id
                FROM channel_contexts
                WHERE channel_identity_id = :identityId
                  AND active_workflow_instance_id IS NOT NULL
                """)
                .param("identityId", channelIdentityId)
                .query(UUID.class)
                .optional();
    }

    @Transactional
    public Optional<InboundMessage> claimInbound(
            UUID channelIdentityId, String providerMessageId, String messageText) {
        if (providerMessageId == null || providerMessageId.isBlank()) return Optional.empty();
        UUID id = UUID.randomUUID();
        try {
            jdbc.sql("""
                    INSERT INTO channel_inbound_messages
                        (id, channel_identity_id, provider_message_id, message_text,
                         status, received_at, processed_at)
                    VALUES (:id, :identityId, :providerMessageId, :messageText,
                            'RECEIVED', :receivedAt, NULL)
                    """)
                    .param("id", id)
                    .param("identityId", channelIdentityId)
                    .param("providerMessageId", providerMessageId.trim())
                    .param("messageText", messageText == null ? "" : messageText.trim())
                    .param("receivedAt", Timestamp.from(Instant.now()))
                    .update();
            return Optional.of(new InboundMessage(id, providerMessageId.trim()));
        } catch (DuplicateKeyException ex) {
            return Optional.empty();
        }
    }

    @Transactional
    public void markInboundProcessed(UUID inboundMessageId) {
        jdbc.sql("""
                UPDATE channel_inbound_messages
                   SET status = 'PROCESSED', processed_at = :processedAt
                 WHERE id = :id
                """)
                .param("processedAt", Timestamp.from(Instant.now()))
                .param("id", inboundMessageId)
                .update();
    }

    public record ChannelIdentity(UUID id, UUID userId, String externalAddress, String status) {}

    public record PendingStep(
            UUID channelIdentityId,
            UUID userId,
            String externalAddress,
            UUID workflowInstanceId,
            UUID workflowStepInstanceId) {}

    public record InboundMessage(UUID id, String providerMessageId) {}
}
