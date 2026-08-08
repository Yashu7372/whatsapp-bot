package com.whatsappbot.document;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class ParallelApprovalRepository {
    private final JdbcTemplate jdbc;

    ApprovalState lock(UUID tenantId,UUID approvalId){
        return jdbc.query("""
            select a.document_id,a.current_step,a.status,d.project_id,d.originator_org_id
              from document_approvals a join documents d on d.id=a.document_id
             where a.tenant_id=? and a.id=? for update of a
            """,rs->rs.next()?new ApprovalState(rs.getObject(1,UUID.class),rs.getInt(2),rs.getString(3),
                rs.getObject(4,UUID.class),rs.getObject(5,UUID.class)):null,tenantId,approvalId);
    }
    String currentParallelGroup(UUID approvalId,int currentStep){
        return jdbc.query("select parallel_group from document_approval_steps where approval_id=? and step_index=?",
                rs->rs.next()?rs.getString(1):null,approvalId,currentStep);
    }
    List<StepRow> group(UUID approvalId,String group){
        return jdbc.query("""
            select id,step_index,step_name,authority_type,assignment_type,assignment_organization_id,
                   assignment_party_role,reviewer_email,required,decision
              from document_approval_steps where approval_id=? and parallel_group=? order by step_index
            """,(rs,n)->new StepRow(rs.getObject(1,UUID.class),rs.getInt(2),rs.getString(3),rs.getString(4),rs.getString(5),
                rs.getObject(6,UUID.class),rs.getString(7),rs.getString(8),rs.getBoolean(9),rs.getString(10)),approvalId,group);
    }
    int decide(UUID stepId,UUID reviewerId,String decision,String comments){
        return jdbc.update("update document_approval_steps set reviewer_id=?,decision=?,comments=?,decided_at=now() where id=? and decision is null",
                reviewerId,decision,comments,stepId);
    }
    void skipOptional(UUID approvalId,String group){
        jdbc.update("update document_approval_steps set decision='SKIPPED',decided_at=now() where approval_id=? and parallel_group=? and required=false and decision is null",approvalId,group);
    }
    Integer nextStep(UUID approvalId,int afterIndex){
        return jdbc.query("select min(step_index) from document_approval_steps where approval_id=? and step_index>? and decision is null",
                rs->rs.next()?(Integer)rs.getObject(1):null,approvalId,afterIndex);
    }
    void advance(UUID tenantId,UUID approvalId,int next){jdbc.update("update document_approvals set current_step=? where tenant_id=? and id=?",next,tenantId,approvalId);}
    void finish(UUID tenantId,UUID approvalId,String status){jdbc.update("update document_approvals set status=?,completed_at=now() where tenant_id=? and id=?",status,tenantId,approvalId);}
    void documentStatus(UUID tenantId,UUID documentId,String status){jdbc.update("update documents set status=?,updated_at=now() where tenant_id=? and id=?",status,tenantId,documentId);}
    void reviewOutcome(UUID tenantId,UUID documentId,String outcome){jdbc.update("update documents set review_outcome=?,updated_at=now() where tenant_id=? and id=?",outcome,tenantId,documentId);}

    record ApprovalState(UUID documentId,int currentStep,String status,UUID projectId,UUID originatorOrganizationId){}
    record StepRow(UUID id,int stepIndex,String stepName,String authorityType,String assignmentType,UUID assignmentOrganizationId,
                   String assignmentPartyRole,String reviewerEmail,boolean required,String decision){}
}
