package com.whatsappbot.document;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
@RequiredArgsConstructor
class DocumentAuthorizationRepository {
    private final JdbcTemplate jdbc;

    DocumentSecurity security(UUID tenantId, UUID documentId) {
        return jdbc.query("""
                select project_id, originator_org_id, security_classification
                  from documents
                 where tenant_id=? and id=?
                """, rs -> rs.next()
                ? new DocumentSecurity(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3))
                : null, tenantId, documentId);
    }

    UUID documentIdForApproval(UUID tenantId, UUID approvalId) {
        return jdbc.query("select document_id from document_approvals where tenant_id=? and id=?",
                rs->rs.next()?rs.getObject(1,UUID.class):null,tenantId,approvalId);
    }

    ApprovalAuthority approvalAuthority(UUID tenantId, UUID approvalId) {
        return jdbc.query("""
                select a.document_id,d.project_id,d.originator_org_id,
                       coalesce(s.authority_type,'TECHNICAL_REVIEW'),
                       coalesce(s.assignment_type,'USER'),s.assignment_organization_id,
                       s.assignment_party_role,s.reviewer_email
                  from document_approvals a
                  join documents d on d.id=a.document_id
                  left join document_approval_steps s
                    on s.approval_id=a.id and s.step_index=a.current_step
                 where a.tenant_id=? and a.id=? and a.status='PENDING'
                """, rs->rs.next()?new ApprovalAuthority(
                        rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                        rs.getString(4),rs.getString(5),rs.getObject(6,UUID.class),rs.getString(7),rs.getString(8)) : null,
                tenantId,approvalId);
    }

    boolean hasGrant(UUID tenantId, UUID documentId, UUID userId, UUID organizationId, String roleCode, String permission) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                  from document_access_grants
                 where tenant_id=? and document_id=? and permission_code=?
                   and (expires_at is null or expires_at > now())
                   and (
                        user_id=?
                        or (? is not null and organization_id=?)
                        or role_code=?
                   )
                """, Integer.class, tenantId, documentId, permission,
                userId, organizationId, organizationId, roleCode);
        return count != null && count > 0;
    }

    boolean assignedToApproval(UUID tenantId, UUID documentId, String reviewerEmail) {
        if (reviewerEmail == null) return false;
        Integer count = jdbc.queryForObject("""
                select count(*)
                  from document_approval_steps s
                  join document_approvals a on a.id=s.approval_id
                 where a.tenant_id=? and a.document_id=? and a.status='PENDING'
                   and s.decision is null and lower(s.reviewer_email)=lower(?)
                """, Integer.class, tenantId, documentId, reviewerEmail);
        return count != null && count > 0;
    }

    record DocumentSecurity(UUID projectId, UUID originatorOrganizationId, String classification) {}
    record ApprovalAuthority(UUID documentId, UUID projectId, UUID originatorOrganizationId,
                             String authorityType, String assignmentType, UUID assignmentOrganizationId,
                             String assignmentPartyRole, String reviewerEmail) {}
}
