package com.whatsappbot.document;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class ApprovalWorklistRepository {
    private final JdbcTemplate jdbc;

    /**
     * Actionable stages for one user, resolved in a single query.
     *
     * <p>The worklist previously loaded every pending stage in the tenant and then ran the full
     * authority evaluation per row inside a try/catch. The assignment test is expressed here as a
     * predicate instead. It answers "is this stage addressed to me"; the contractual authority
     * check still runs in the service, so this narrows the set rather than replacing the policy.
     */
    List<Row> pendingFor(UUID tenantId,UUID userId,String email,UUID organizationId){
        return jdbc.query(baseQuery("""
             and (
                  (s.assignment_type='USER' and ? is not null and lower(s.reviewer_email)=lower(?))
               or (s.assignment_type='ORGANIZATION' and ? is not null and s.assignment_organization_id=?)
               or (s.assignment_type='PARTY_ROLE' and ? is not null and exists (
                      select 1 from project_participants pp
                       where pp.tenant_id=a.tenant_id and pp.project_id=d.project_id
                         and pp.organization_id=? and pp.party_role=s.assignment_party_role and pp.active=true))
               or ? is null
             )
            """),MAPPER,tenantId,email,email,organizationId,organizationId,organizationId,organizationId,userId);
    }

    List<Row> pending(UUID tenantId){
        return jdbc.query(baseQuery(""),MAPPER,tenantId);
    }

    private static String baseQuery(String extra){
        return """
            select s.id,a.id,a.document_id,d.project_id,d.document_code,d.title,
                   s.step_index,s.step_name,s.authority_type,s.assignment_type,
                   s.assignment_organization_id,s.assignment_party_role,s.reviewer_email,
                   s.required,s.parallel_group,s.due_at,s.escalated_at,a.started_at
              from document_approval_steps s
              join document_approvals a on a.id=s.approval_id
              join documents d on d.id=a.document_id
             where a.tenant_id=? and a.status='PENDING' and s.decision is null
               and (s.step_index=a.current_step or (
                    s.parallel_group is not null and s.parallel_group=(
                        select x.parallel_group from document_approval_steps x
                         where x.approval_id=a.id and x.step_index=a.current_step)))
            """+extra+"""
             order by coalesce(s.due_at,'9999-12-31'::timestamp),a.started_at,s.step_index
            """;
    }

    private static final org.springframework.jdbc.core.RowMapper<Row> MAPPER=(rs,n)->new Row(
            rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
            rs.getObject(4,UUID.class),rs.getString(5),rs.getString(6),rs.getInt(7),rs.getString(8),
            rs.getString(9),rs.getString(10),rs.getObject(11,UUID.class),rs.getString(12),rs.getString(13),
            rs.getBoolean(14),rs.getString(15),ts(rs.getTimestamp(16)),ts(rs.getTimestamp(17)),ts(rs.getTimestamp(18)));
    List<Row> overdue(UUID tenantId){return pending(tenantId).stream().filter(r->r.dueAt()!=null&&r.dueAt().isBefore(LocalDateTime.now())&&r.escalatedAt()==null).toList();}
    void markEscalated(UUID stepId){jdbc.update("update document_approval_steps set escalated_at=now() where id=? and escalated_at is null",stepId);}
    void enqueueEscalation(UUID tenantId,Row r){jdbc.update("""
        insert into workflow_notification_outbox(tenant_id,project_id,document_id,approval_id,approval_step_id,event_type,target_organization_id,target_party_role,payload)
        values(?,?,?,?,?,'APPROVAL_OVERDUE',?,?,jsonb_build_object('documentCode',?,'title',?,'stepName',?,'authority',?,'dueAt',?)) on conflict do nothing
        """,tenantId,r.projectId(),r.documentId(),r.approvalId(),r.stepId(),r.assignmentOrganizationId(),r.assignmentPartyRole(),r.documentCode(),r.title(),r.stepName(),r.authorityType(),r.dueAt()!=null?r.dueAt().toString():null);}
    private static LocalDateTime ts(java.sql.Timestamp t){return t==null?null:t.toLocalDateTime();}
    record Row(UUID stepId,UUID approvalId,UUID documentId,UUID projectId,String documentCode,String title,int stepIndex,String stepName,
               String authorityType,String assignmentType,UUID assignmentOrganizationId,String assignmentPartyRole,String reviewerEmail,
               boolean required,String parallelGroup,LocalDateTime dueAt,LocalDateTime escalatedAt,LocalDateTime startedAt){}
}
