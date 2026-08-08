package com.whatsappbot.document;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class WorkflowNotificationEventService {
    private final JdbcTemplate jdbc;

    @Transactional
    public int enqueueCurrentAssignments(UUID tenantId, UUID approvalId) {
        String group = jdbc.query("""
                select s.parallel_group
                  from document_approvals a
                  join document_approval_steps s on s.approval_id=a.id and s.step_index=a.current_step
                 where a.tenant_id=? and a.id=? and a.status='PENDING'
                """, rs -> rs.next() ? rs.getString(1) : null, tenantId, approvalId);
        String extra = group == null || group.isBlank()
                ? "s.step_index=a.current_step"
                : "s.parallel_group=?";
        Object[] args = group == null || group.isBlank()
                ? new Object[]{tenantId, approvalId}
                : new Object[]{tenantId, approvalId, group};
        return jdbc.update("""
                insert into workflow_notification_outbox(
                    tenant_id,project_id,document_id,approval_id,approval_step_id,event_type,
                    target_user_id,target_organization_id,target_party_role,payload)
                select a.tenant_id,d.project_id,a.document_id,a.id,s.id,'APPROVAL_ASSIGNED',
                       case when s.assignment_type='USER' then u.id end,
                       case when s.assignment_type='ORGANIZATION' then s.assignment_organization_id end,
                       case when s.assignment_type='PARTY_ROLE' then s.assignment_party_role end,
                       jsonb_build_object('documentCode',d.document_code,'title',d.title,'stepName',s.step_name,
                                          'authority',s.authority_type,'dueAt',s.due_at,'parallelGroup',s.parallel_group)
                  from document_approvals a
                  join documents d on d.id=a.document_id
                  join document_approval_steps s on s.approval_id=a.id
                  left join tenant_users u on u.tenant_id=a.tenant_id and lower(u.email)=lower(s.reviewer_email) and u.active=true
                 where a.tenant_id=? and a.id=? and a.status='PENDING' and s.decision is null and """ + extra + """
                   and (s.assignment_type<>'USER' or u.id is not null)
                on conflict do nothing
                """, args);
    }

    @Transactional
    public int enqueueApprovalResult(UUID tenantId, UUID approvalId) {
        return jdbc.update("""
                insert into workflow_notification_outbox(
                    tenant_id,project_id,document_id,approval_id,event_type,target_user_id,payload)
                select a.tenant_id,d.project_id,a.document_id,a.id,'APPROVAL_RESULT',a.initiated_by,
                       jsonb_build_object('documentCode',d.document_code,'title',d.title,'status',a.status,
                                          'completedAt',a.completed_at,'reviewOutcome',d.review_outcome)
                  from document_approvals a join documents d on d.id=a.document_id
                 where a.tenant_id=? and a.id=? and a.status in ('APPROVED','REJECTED') and a.initiated_by is not null
                on conflict do nothing
                """, tenantId, approvalId);
    }

    @Transactional
    public int enqueueSlaNotifications() {
        int dueSoon = jdbc.update("""
                insert into workflow_notification_outbox(
                    tenant_id,project_id,document_id,approval_id,approval_step_id,event_type,
                    target_user_id,target_organization_id,target_party_role,payload)
                select a.tenant_id,d.project_id,a.document_id,a.id,s.id,'APPROVAL_DUE_SOON',
                       case when s.assignment_type='USER' then u.id end,
                       case when s.assignment_type='ORGANIZATION' then s.assignment_organization_id end,
                       case when s.assignment_type='PARTY_ROLE' then s.assignment_party_role end,
                       jsonb_build_object('documentCode',d.document_code,'title',d.title,'stepName',s.step_name,
                                          'authority',s.authority_type,'dueAt',s.due_at,'parallelGroup',s.parallel_group)
                  from document_approvals a
                  join documents d on d.id=a.document_id
                  join document_approval_steps s on s.approval_id=a.id
                  left join tenant_users u on u.tenant_id=a.tenant_id and lower(u.email)=lower(s.reviewer_email) and u.active=true
                 where a.status='PENDING' and s.decision is null and s.due_at>now() and s.due_at<=now()+interval '24 hours'
                   and (s.step_index=a.current_step or (s.parallel_group is not null and s.parallel_group=(select parallel_group from document_approval_steps where approval_id=a.id and step_index=a.current_step)))
                   and (s.assignment_type<>'USER' or u.id is not null)
                on conflict do nothing
                """);
        int overdue = jdbc.update("""
                insert into workflow_notification_outbox(
                    tenant_id,project_id,document_id,approval_id,approval_step_id,event_type,
                    target_user_id,target_organization_id,target_party_role,payload)
                select a.tenant_id,d.project_id,a.document_id,a.id,s.id,'APPROVAL_OVERDUE',
                       case when s.assignment_type='USER' then u.id end,
                       case when s.assignment_type='ORGANIZATION' then s.assignment_organization_id end,
                       case when s.assignment_type='PARTY_ROLE' then s.assignment_party_role end,
                       jsonb_build_object('documentCode',d.document_code,'title',d.title,'stepName',s.step_name,
                                          'authority',s.authority_type,'dueAt',s.due_at,'parallelGroup',s.parallel_group)
                  from document_approvals a
                  join documents d on d.id=a.document_id
                  join document_approval_steps s on s.approval_id=a.id
                  left join tenant_users u on u.tenant_id=a.tenant_id and lower(u.email)=lower(s.reviewer_email) and u.active=true
                 where a.status='PENDING' and s.decision is null and s.due_at<=now()
                   and (s.step_index=a.current_step or (s.parallel_group is not null and s.parallel_group=(select parallel_group from document_approval_steps where approval_id=a.id and step_index=a.current_step)))
                   and (s.assignment_type<>'USER' or u.id is not null)
                on conflict do nothing
                """);
        return dueSoon + overdue;
    }

    @Transactional
    public int enqueueTransmittalIssued(UUID tenantId, UUID transmittalId) {
        return jdbc.update("""
                insert into workflow_notification_outbox(
                    tenant_id,project_id,transmittal_id,event_type,target_organization_id,payload)
                select t.tenant_id,t.project_id,t.id,'TRANSMITTAL_ISSUED',r.recipient_organization_id,
                       jsonb_build_object('transmittalNo',t.transmittal_no,'subject',t.subject,'purpose',t.purpose,
                                          'senderOrganizationId',t.sender_organization_id,'issuedAt',t.issued_at)
                  from document_transmittals t
                  join document_transmittal_recipients r on r.transmittal_id=t.id
                 where t.tenant_id=? and t.id=? and t.status in ('ISSUED','PARTIALLY_ACKNOWLEDGED','ACKNOWLEDGED')
                on conflict do nothing
                """, tenantId, transmittalId);
    }

    @Transactional
    public int enqueueTransmittalAcknowledged(UUID tenantId, UUID transmittalId, UUID recipientOrganizationId) {
        return jdbc.update("""
                insert into workflow_notification_outbox(
                    tenant_id,project_id,transmittal_id,event_type,target_organization_id,payload)
                select t.tenant_id,t.project_id,t.id,'TRANSMITTAL_ACKNOWLEDGED',t.sender_organization_id,
                       jsonb_build_object('transmittalNo',t.transmittal_no,'subject',t.subject,'recipientOrganizationId',?,
                                          'status',t.status)
                  from document_transmittals t where t.tenant_id=? and t.id=?
                on conflict do nothing
                """, recipientOrganizationId, tenantId, transmittalId);
    }
}
