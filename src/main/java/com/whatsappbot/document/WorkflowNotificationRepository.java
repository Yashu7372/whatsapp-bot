package com.whatsappbot.document;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class WorkflowNotificationRepository {

    private final JdbcTemplate jdbc;

    /**
     * Claims pending events into PROCESSING.
     *
     * <p>The CTE plus {@code UPDATE ... RETURNING} is one statement, so two pods never claim the
     * same row. The status deliberately stops at PROCESSING: the caller marks DELIVERED only once
     * the derived rows exist, and {@link #recoverStaleOutbox(int)} rescues anything left behind.
     */
    List<OutboxRow> claimOutbox(int batchSize) {
        return jdbc.query("""
                with claimed as (
                    select id from workflow_notification_outbox
                     where status='PENDING'
                     order by created_at
                     limit ? for update skip locked
                )
                update workflow_notification_outbox o
                   set status='PROCESSING',claimed_at=now(),dispatched_at=now()
                  from claimed c where o.id=c.id
                returning o.id,o.tenant_id,o.project_id,o.document_id,o.transmittal_id,o.event_type,
                          o.target_user_id,o.target_organization_id,o.target_party_role,o.payload::text,o.created_at
                """, (rs, n) -> new OutboxRow(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, UUID.class), rs.getObject(5, UUID.class), rs.getString(6),
                rs.getObject(7, UUID.class), rs.getObject(8, UUID.class), rs.getString(9),
                rs.getString(10), ts(rs.getTimestamp(11))), batchSize);
    }

    void markOutboxDelivered(UUID id) {
        jdbc.update("update workflow_notification_outbox set status='DELIVERED',delivered_at=now(),claimed_at=null where id=?", id);
    }

    int recoverStaleOutbox(int staleMinutes) {
        return jdbc.update("""
                update workflow_notification_outbox
                   set status='PENDING',claimed_at=null
                 where status='PROCESSING' and claimed_at < now() - (? * interval '1 minute')
                """, staleMinutes);
    }

    /**
     * Resolves an event target into concrete users: a named user, everyone holding a notifiable
     * role in a target organization, or everyone holding a notifiable role in an organization
     * acting under the target party role on the event's project.
     */
    List<Recipient> recipients(OutboxRow row, List<String> notifiableRoles) {
        String roles = inClause(notifiableRoles.size());
        Object[] args = new Object[6 + notifiableRoles.size() * 2 + 3 + 2];
        int i = 0;
        args[i++] = row.tenantId();
        args[i++] = row.targetUserId();
        args[i++] = row.targetUserId();
        args[i++] = row.targetOrganizationId();
        args[i++] = row.targetOrganizationId();
        for (String role : notifiableRoles) args[i++] = role;
        args[i++] = row.targetPartyRole();
        args[i++] = row.tenantId();
        args[i++] = row.projectId();
        args[i++] = row.targetPartyRole();
        for (String role : notifiableRoles) args[i++] = role;
        args[i++] = row.documentId();
        args[i] = row.documentId();

        return jdbc.query("""
                select distinct u.id,u.email,u.notification_phone,u.email_notifications_enabled,u.whatsapp_notifications_enabled
                  from tenant_users u
                 where u.tenant_id=? and u.active=true and (
                       (? is not null and u.id=?)
                    or (? is not null and u.organization_id=? and u.role in %s)
                    or (? is not null and exists (
                        select 1 from project_participants pp
                         where pp.tenant_id=? and pp.project_id=? and pp.organization_id=u.organization_id
                           and pp.party_role=? and pp.active=true
                    ) and u.role in %s)
                 )
                   -- A notification carries the document code and title. Expanding an organization
                   -- or party-role target must therefore not reach past the document's own
                   -- classification, or the subject line becomes a disclosure channel for a
                   -- document the reader is refused when they click through.
                   and (? is null or exists (
                        select 1 from documents d
                         where d.id=? and d.tenant_id=u.tenant_id
                           and (
                                d.security_classification='PROJECT'
                             or (d.originator_org_id is not null and d.originator_org_id=u.organization_id)
                             or exists (select 1 from document_access_grants g
                                         where g.tenant_id=d.tenant_id and g.document_id=d.id
                                           and (g.expires_at is null or g.expires_at > now())
                                           and (g.user_id=u.id or (u.organization_id is not null and g.organization_id=u.organization_id)
                                                or g.role_code=u.role))
                             or exists (select 1 from document_approval_steps s
                                          join document_approvals a on a.id=s.approval_id
                                         where a.tenant_id=d.tenant_id and a.document_id=d.id
                                           and a.status='PENDING' and s.decision is null
                                           and (
                                                (s.assignment_type='USER' and lower(s.reviewer_email)=lower(u.email))
                                             or (s.assignment_type='ORGANIZATION' and s.assignment_organization_id=u.organization_id)
                                             or (s.assignment_type='PARTY_ROLE' and exists (
                                                    select 1 from project_participants pp3
                                                     where pp3.tenant_id=d.tenant_id and pp3.project_id=d.project_id
                                                       and pp3.organization_id=u.organization_id
                                                       and pp3.party_role=s.assignment_party_role and pp3.active=true))
                                           ))
                           )
                   ))
                """.formatted(roles, roles),
                (rs, n) -> new Recipient(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                        rs.getBoolean(4), rs.getBoolean(5)), args);
    }

    void insertInApp(OutboxRow row, Recipient user, String title, String body) {
        jdbc.update("""
                insert into workflow_in_app_notifications(
                    tenant_id,user_id,outbox_id,project_id,document_id,transmittal_id,event_type,title,body,payload)
                values(?,?,?,?,?,?,?,?,?,?::jsonb) on conflict do nothing
                """, row.tenantId(), user.userId(), row.id(), row.projectId(), row.documentId(),
                row.transmittalId(), row.eventType(), title, body, row.payload());
    }

    void insertDelivery(OutboxRow row, Recipient user, NotificationChannel channel,
                        String destination, String subject, String body) {
        jdbc.update("""
                insert into workflow_notification_deliveries(
                    tenant_id,outbox_id,user_id,channel,destination,subject,body)
                values(?,?,?,?,?,?,?) on conflict do nothing
                """, row.tenantId(), row.id(), user.userId(), channel.name(), destination, subject, body);
    }

    /**
     * Claims deliveries that are due for an attempt. SKIPPED is included so that a delivery parked
     * while its channel was switched off is retried once the channel is switched back on.
     */
    List<DeliveryRow> claimDeliveries(int batchSize, int maxAttempts) {
        return jdbc.query("""
                with claimed as (
                    select id from workflow_notification_deliveries
                     where status in ('PENDING','FAILED','SKIPPED') and next_attempt_at<=now() and attempt_count<?
                     order by next_attempt_at,created_at
                     limit ? for update skip locked
                )
                update workflow_notification_deliveries d
                   set status='PROCESSING',claimed_at=now(),updated_at=now()
                  from claimed c where d.id=c.id
                returning d.id,d.tenant_id,d.outbox_id,d.user_id,d.channel,d.destination,d.subject,d.body,d.attempt_count
                """, (rs, n) -> new DeliveryRow(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), NotificationChannel.of(rs.getString(5)),
                rs.getString(6), rs.getString(7), rs.getString(8), rs.getInt(9)), maxAttempts, batchSize);
    }

    void sent(UUID id) {
        jdbc.update("""
                update workflow_notification_deliveries
                   set status='SENT',attempt_count=attempt_count+1,sent_at=now(),claimed_at=null,
                       last_error=null,updated_at=now()
                 where id=?
                """, id);
    }

    /**
     * Parks a delivery whose channel is currently disabled. The attempt is deliberately not counted
     * — a disabled channel is not a failed delivery, and counting it would exhaust the retry budget
     * of every notification queued before an operator enables the channel.
     */
    void skipped(UUID id, String reason, int retryMinutes) {
        jdbc.update("""
                update workflow_notification_deliveries
                   set status='SKIPPED',next_attempt_at=now()+(? * interval '1 minute'),claimed_at=null,
                       last_error=?,updated_at=now()
                 where id=?
                """, retryMinutes, truncate(reason), id);
    }

    void failed(UUID id, int attemptNumber, boolean exhausted, int backoffMinutes, String error) {
        jdbc.update("""
                update workflow_notification_deliveries
                   set status=?,attempt_count=?,next_attempt_at=now()+(? * interval '1 minute'),
                       claimed_at=null,last_error=?,updated_at=now()
                 where id=?
                """, exhausted ? NotificationDeliveryStatus.DEAD.name() : NotificationDeliveryStatus.FAILED.name(),
                attemptNumber, backoffMinutes, truncate(error), id);
    }

    int recoverStuckDeliveries(int staleMinutes) {
        return jdbc.update("""
                update workflow_notification_deliveries
                   set status='FAILED',claimed_at=null,next_attempt_at=now(),
                       last_error='Recovered stale PROCESSING claim',updated_at=now()
                 where status='PROCESSING' and claimed_at < now() - (? * interval '1 minute')
                """, staleMinutes);
    }

    /**
     * Removes settled events older than the retention window. Children cascade from the outbox, so
     * the guards make sure nothing a user has not yet read or a worker has not yet finished is
     * taken with them.
     */
    int purgeSettled(int retentionDays) {
        return jdbc.update("""
                delete from workflow_notification_outbox o
                 where o.status='DELIVERED'
                   and o.created_at < now() - (? * interval '1 day')
                   and not exists (select 1 from workflow_in_app_notifications a
                                    where a.outbox_id=o.id and a.read_at is null)
                   and not exists (select 1 from workflow_notification_deliveries d
                                    where d.outbox_id=o.id and d.status not in ('SENT','DEAD'))
                """, retentionDays);
    }

    List<InAppView> notifications(UUID tenantId, UUID userId, int limit) {
        return jdbc.query("""
                select id,event_type,title,body,project_id,document_id,transmittal_id,payload::text,read_at,created_at
                  from workflow_in_app_notifications where tenant_id=? and user_id=?
                 order by created_at desc limit ?
                """, (rs, n) -> new InAppView(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3),
                rs.getString(4), rs.getObject(5, UUID.class), rs.getObject(6, UUID.class),
                rs.getObject(7, UUID.class), rs.getString(8), ts(rs.getTimestamp(9)), ts(rs.getTimestamp(10))),
                tenantId, userId, limit);
    }

    int unreadCount(UUID tenantId, UUID userId) {
        Integer n = jdbc.queryForObject(
                "select count(*) from workflow_in_app_notifications where tenant_id=? and user_id=? and read_at is null",
                Integer.class, tenantId, userId);
        return n == null ? 0 : n;
    }

    int markRead(UUID tenantId, UUID userId, UUID id) {
        return jdbc.update("update workflow_in_app_notifications set read_at=coalesce(read_at,now()) where tenant_id=? and user_id=? and id=?",
                tenantId, userId, id);
    }

    int markAllRead(UUID tenantId, UUID userId) {
        return jdbc.update("update workflow_in_app_notifications set read_at=now() where tenant_id=? and user_id=? and read_at is null",
                tenantId, userId);
    }

    void updatePreferences(UUID tenantId, UUID userId, boolean emailEnabled, boolean whatsappEnabled, String phone) {
        jdbc.update("""
                update tenant_users
                   set email_notifications_enabled=?,whatsapp_notifications_enabled=?,notification_phone=?,updated_at=now()
                 where tenant_id=? and id=? and active=true
                """, emailEnabled, whatsappEnabled, phone == null || phone.isBlank() ? null : phone.trim(), tenantId, userId);
    }

    Preferences preferences(UUID tenantId, UUID userId) {
        return jdbc.query("""
                select email_notifications_enabled,whatsapp_notifications_enabled,notification_phone
                  from tenant_users where tenant_id=? and id=? and active=true
                """, rs -> rs.next() ? new Preferences(rs.getBoolean(1), rs.getBoolean(2), rs.getString(3)) : null,
                tenantId, userId);
    }

    List<DeliveryAudit> deliveryAudit(UUID tenantId, int limit) {
        return jdbc.query("""
                select d.id,d.channel,d.destination,d.status,d.attempt_count,d.last_error,d.sent_at,d.created_at,
                       o.event_type,u.email
                  from workflow_notification_deliveries d
                  join workflow_notification_outbox o on o.id=d.outbox_id
                  join tenant_users u on u.id=d.user_id
                 where d.tenant_id=? order by d.created_at desc limit ?
                """, (rs, n) -> new DeliveryAudit(rs.getObject(1, UUID.class), rs.getString(2),
                mask(rs.getString(3)), rs.getString(4), rs.getInt(5), rs.getString(6),
                ts(rs.getTimestamp(7)), ts(rs.getTimestamp(8)), rs.getString(9), rs.getString(10)), tenantId, limit);
    }

    private static String inClause(int size) {
        return "(" + String.join(",", java.util.Collections.nCopies(Math.max(size, 1), "?")) + ")";
    }

    private static LocalDateTime ts(Timestamp t) {
        return t == null ? null : t.toLocalDateTime();
    }

    /**
     * Partially masks a delivery destination.
     *
     * <p>The audit exists to answer "did this reach the right channel and why did it fail", which
     * a partial value still answers. Returning every address and phone number in full turned an
     * operational screen into a directory of personal contact details.
     */
    private static String mask(String destination) {
        if (destination == null || destination.isBlank()) return destination;
        int at = destination.indexOf('@');
        if (at > 0) {
            String local = destination.substring(0, at);
            String visible = local.substring(0, Math.min(2, local.length()));
            return visible + "***" + destination.substring(at);
        }
        int keep = Math.min(4, destination.length());
        return "***" + destination.substring(destination.length() - keep);
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 1800 ? s : s.substring(0, 1800);
    }

    record OutboxRow(UUID id, UUID tenantId, UUID projectId, UUID documentId, UUID transmittalId, String eventType,
                     UUID targetUserId, UUID targetOrganizationId, String targetPartyRole, String payload,
                     LocalDateTime createdAt) {}

    record Recipient(UUID userId, String email, String phone, boolean emailEnabled, boolean whatsappEnabled) {}

    record DeliveryRow(UUID id, UUID tenantId, UUID outboxId, UUID userId, NotificationChannel channel,
                       String destination, String subject, String body, int attempts) {}

    record InAppView(UUID id, String eventType, String title, String body, UUID projectId, UUID documentId,
                     UUID transmittalId, String payload, LocalDateTime readAt, LocalDateTime createdAt) {}

    record Preferences(boolean emailEnabled, boolean whatsappEnabled, String whatsappNumber) {}

    record DeliveryAudit(UUID id, String channel, String destination, String status, int attempts, String lastError,
                         LocalDateTime sentAt, LocalDateTime createdAt, String eventType, String userEmail) {}
}
