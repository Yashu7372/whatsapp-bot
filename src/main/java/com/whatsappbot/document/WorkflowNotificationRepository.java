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

    List<OutboxRow> claimOutbox(int batchSize) {
        return jdbc.query("""
                with claimed as (
                    select id from workflow_notification_outbox
                     where status='PENDING'
                     order by created_at
                     limit ? for update skip locked
                )
                update workflow_notification_outbox o
                   set status='DELIVERED',dispatched_at=now(),delivered_at=now()
                  from claimed c where o.id=c.id
                returning o.id,o.tenant_id,o.project_id,o.document_id,o.transmittal_id,o.event_type,
                          o.target_user_id,o.target_organization_id,o.target_party_role,o.payload::text,o.created_at
                """, (rs,n)->new OutboxRow(
                rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getObject(4,UUID.class),
                rs.getObject(5,UUID.class),rs.getString(6),rs.getObject(7,UUID.class),rs.getObject(8,UUID.class),
                rs.getString(9),rs.getString(10),ts(rs.getTimestamp(11))), batchSize);
    }

    List<Recipient> recipients(OutboxRow row) {
        return jdbc.query("""
                select distinct u.id,u.email,u.notification_phone,u.email_notifications_enabled,u.whatsapp_notifications_enabled
                  from tenant_users u
                 where u.tenant_id=? and u.active=true and (
                       (? is not null and u.id=?)
                    or (? is not null and u.organization_id=? and u.role in ('ADMIN','MANAGER','REVIEWER'))
                    or (? is not null and exists (
                        select 1 from project_participants pp
                         where pp.tenant_id=? and pp.project_id=? and pp.organization_id=u.organization_id
                           and pp.party_role=? and pp.active=true
                    ) and u.role in ('ADMIN','MANAGER','REVIEWER'))
                 )
                """, (rs,n)->new Recipient(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getBoolean(4),rs.getBoolean(5)),
                row.tenantId(),row.targetUserId(),row.targetUserId(),row.targetOrganizationId(),row.targetOrganizationId(),
                row.targetPartyRole(),row.tenantId(),row.projectId(),row.targetPartyRole());
    }

    void insertInApp(OutboxRow row, Recipient user, String title, String body) {
        jdbc.update("""
                insert into workflow_in_app_notifications(
                    tenant_id,user_id,outbox_id,project_id,document_id,transmittal_id,event_type,title,body,payload)
                values(?,?,?,?,?,?,?,?,?,?::jsonb) on conflict do nothing
                """,row.tenantId(),user.userId(),row.id(),row.projectId(),row.documentId(),row.transmittalId(),
                row.eventType(),title,body,row.payload());
    }

    void insertDelivery(OutboxRow row, Recipient user, String channel, String destination, String subject, String body) {
        jdbc.update("""
                insert into workflow_notification_deliveries(
                    tenant_id,outbox_id,user_id,channel,destination,subject,body)
                values(?,?,?,?,?,?,?) on conflict do nothing
                """,row.tenantId(),row.id(),user.userId(),channel,destination,subject,body);
    }

    List<DeliveryRow> claimDeliveries(int batchSize, int maxAttempts) {
        return jdbc.query("""
                with claimed as (
                    select id from workflow_notification_deliveries
                     where status in ('PENDING','FAILED') and next_attempt_at<=now() and attempt_count<?
                     order by next_attempt_at,created_at
                     limit ? for update skip locked
                )
                update workflow_notification_deliveries d
                   set status='PROCESSING',claimed_at=now(),updated_at=now()
                  from claimed c where d.id=c.id
                returning d.id,d.tenant_id,d.outbox_id,d.user_id,d.channel,d.destination,d.subject,d.body,d.attempt_count
                """,(rs,n)->new DeliveryRow(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getObject(4,UUID.class),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getInt(9)),
                maxAttempts,batchSize);
    }

    void sent(UUID id){jdbc.update("update workflow_notification_deliveries set status='SENT',attempt_count=attempt_count+1,sent_at=now(),claimed_at=null,last_error=null,updated_at=now() where id=?",id);}
    void skipped(UUID id,String reason){jdbc.update("update workflow_notification_deliveries set status='SKIPPED',attempt_count=attempt_count+1,claimed_at=null,last_error=?,updated_at=now() where id=?",limit(reason),id);}
    void failed(UUID id,int previousAttempts,int maxAttempts,String error){
        int nextAttempt=previousAttempts+1;
        String status=nextAttempt>=maxAttempts?"DEAD":"FAILED";
        long minutes=nextAttempt<=1?1:nextAttempt==2?5:nextAttempt==3?15:60;
        jdbc.update("update workflow_notification_deliveries set status=?,attempt_count=?,next_attempt_at=now()+(? * interval '1 minute'),claimed_at=null,last_error=?,updated_at=now() where id=?",
                status,nextAttempt,minutes,limit(error),id);
    }
    int recoverStuck(){return jdbc.update("update workflow_notification_deliveries set status='FAILED',claimed_at=null,next_attempt_at=now(),last_error='Recovered stale PROCESSING claim',updated_at=now() where status='PROCESSING' and claimed_at<now()-interval '10 minutes'");}

    List<InAppView> notifications(UUID tenantId,UUID userId,int limit){
        return jdbc.query("""
                select id,event_type,title,body,project_id,document_id,transmittal_id,payload::text,read_at,created_at
                  from workflow_in_app_notifications where tenant_id=? and user_id=?
                 order by created_at desc limit ?
                """,(rs,n)->new InAppView(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getObject(5,UUID.class),rs.getObject(6,UUID.class),rs.getObject(7,UUID.class),rs.getString(8),
                ts(rs.getTimestamp(9)),ts(rs.getTimestamp(10))),tenantId,userId,limit);
    }
    int unreadCount(UUID tenantId,UUID userId){Integer n=jdbc.queryForObject("select count(*) from workflow_in_app_notifications where tenant_id=? and user_id=? and read_at is null",Integer.class,tenantId,userId);return n==null?0:n;}
    int markRead(UUID tenantId,UUID userId,UUID id){return jdbc.update("update workflow_in_app_notifications set read_at=coalesce(read_at,now()) where tenant_id=? and user_id=? and id=?",tenantId,userId,id);}
    int markAllRead(UUID tenantId,UUID userId){return jdbc.update("update workflow_in_app_notifications set read_at=now() where tenant_id=? and user_id=? and read_at is null",tenantId,userId);}

    void updatePreferences(UUID tenantId,UUID userId,boolean emailEnabled,boolean whatsappEnabled,String phone){
        jdbc.update("update tenant_users set email_notifications_enabled=?,whatsapp_notifications_enabled=?,notification_phone=?,updated_at=now() where tenant_id=? and id=? and active=true",
                emailEnabled,whatsappEnabled,phone==null||phone.isBlank()?null:phone.trim(),tenantId,userId);
    }
    Preferences preferences(UUID tenantId,UUID userId){return jdbc.query("select email_notifications_enabled,whatsapp_notifications_enabled,notification_phone from tenant_users where tenant_id=? and id=? and active=true",
            rs->rs.next()?new Preferences(rs.getBoolean(1),rs.getBoolean(2),rs.getString(3)):null,tenantId,userId);}

    List<DeliveryAudit> deliveryAudit(UUID tenantId,int limit){return jdbc.query("""
            select d.id,d.channel,d.destination,d.status,d.attempt_count,d.last_error,d.sent_at,d.created_at,o.event_type,u.email
              from workflow_notification_deliveries d join workflow_notification_outbox o on o.id=d.outbox_id
              join tenant_users u on u.id=d.user_id
             where d.tenant_id=? order by d.created_at desc limit ?
            """,(rs,n)->new DeliveryAudit(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5),rs.getString(6),
            ts(rs.getTimestamp(7)),ts(rs.getTimestamp(8)),rs.getString(9),rs.getString(10)),tenantId,limit);}

    private static LocalDateTime ts(Timestamp t){return t==null?null:t.toLocalDateTime();}
    private static String limit(String s){if(s==null)return null;return s.length()>1800?s.substring(0,1800):s;}

    record OutboxRow(UUID id,UUID tenantId,UUID projectId,UUID documentId,UUID transmittalId,String eventType,
                     UUID targetUserId,UUID targetOrganizationId,String targetPartyRole,String payload,LocalDateTime createdAt){}
    record Recipient(UUID userId,String email,String phone,boolean emailEnabled,boolean whatsappEnabled){}
    record DeliveryRow(UUID id,UUID tenantId,UUID outboxId,UUID userId,String channel,String destination,String subject,String body,int attempts){}
    record InAppView(UUID id,String eventType,String title,String body,UUID projectId,UUID documentId,UUID transmittalId,String payload,LocalDateTime readAt,LocalDateTime createdAt){}
    record Preferences(boolean emailEnabled,boolean whatsappEnabled,String whatsappNumber){}
    record DeliveryAudit(UUID id,String channel,String destination,String status,int attempts,String lastError,LocalDateTime sentAt,LocalDateTime createdAt,String eventType,String userEmail){}
}
