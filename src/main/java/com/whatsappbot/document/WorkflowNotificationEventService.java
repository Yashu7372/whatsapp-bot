package com.whatsappbot.document;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raises the time-based approval notifications that no database trigger can produce.
 *
 * <p>Event-driven notifications — assignment, approval result, transmittal issue and
 * acknowledgement — are raised by triggers defined in {@code V33__document_notification_delivery.sql}
 * so that the event and the business change share one transaction by construction. This class
 * previously carried a second, unreachable Java implementation of those same four events; it was
 * removed because a duplicate that nothing calls is a trap: an edit to it changes no behaviour,
 * and wiring it back up would double-post TRANSMITTAL_ACKNOWLEDGED, the one event that has no
 * deduplicating unique index.
 *
 * <p>SLA scanning genuinely belongs here: "this step became overdue" is the passage of time, not a
 * row change, so nothing in the database can observe it.
 */
@Service
@RequiredArgsConstructor
public class WorkflowNotificationEventService {

    private final JdbcTemplate jdbc;

    /**
     * Enqueues due-soon and overdue notices for pending steps on the current stage. The unique
     * index on (step, event type, target) makes this idempotent, so a scan interval shorter than
     * the SLA window cannot produce repeated nagging.
     */
    @Transactional
    public int enqueueSlaNotifications(int dueSoonHours) {
        int dueSoon = jdbc.update(slaStatement("APPROVAL_DUE_SOON",
                "s.due_at>now() and s.due_at<=now()+(? * interval '1 hour')"), dueSoonHours);
        int overdue = jdbc.update(slaStatement("APPROVAL_OVERDUE", "s.due_at<=now()"));
        return dueSoon + overdue;
    }

    private static String slaStatement(String eventType, String dueClause) {
        return """
                insert into workflow_notification_outbox(
                    tenant_id,project_id,document_id,approval_id,approval_step_id,event_type,
                    target_user_id,target_organization_id,target_party_role,payload)
                select a.tenant_id,d.project_id,a.document_id,a.id,s.id,'%s',
                       case when s.assignment_type='USER' then u.id end,
                       case when s.assignment_type='ORGANIZATION' then s.assignment_organization_id end,
                       case when s.assignment_type='PARTY_ROLE' then s.assignment_party_role end,
                       jsonb_build_object('documentCode',d.document_code,'title',d.title,'stepName',s.step_name,
                                          'authority',s.authority_type,'dueAt',s.due_at,'parallelGroup',s.parallel_group)
                  from document_approvals a
                  join documents d on d.id=a.document_id
                  join document_approval_steps s on s.approval_id=a.id
                  left join tenant_users u on u.tenant_id=a.tenant_id and lower(u.email)=lower(s.reviewer_email) and u.active=true
                 where a.status='PENDING' and s.decision is null and %s
                   and (s.step_index=a.current_step
                        or (s.parallel_group is not null
                            and s.parallel_group=(select parallel_group from document_approval_steps
                                                   where approval_id=a.id and step_index=a.current_step)))
                   and (s.assignment_type<>'USER' or u.id is not null)
                on conflict do nothing
                """.formatted(eventType, dueClause);
    }
}
