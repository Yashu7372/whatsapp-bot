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
                   and s.status='PENDING' and lower(s.reviewer_email)=lower(?)
                """, Integer.class, tenantId, documentId, reviewerEmail);
        return count != null && count > 0;
    }

    record DocumentSecurity(UUID projectId, UUID originatorOrganizationId, String classification) {}
}
