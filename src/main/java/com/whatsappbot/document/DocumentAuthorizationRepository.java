package com.whatsappbot.document;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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

    boolean hasGrant(UUID tenantId, UUID documentId, UUID userId, UUID organizationId, String roleCode, String permission) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                  from document_access_grants
                 where tenant_id=? and document_id=? and permission_code=?
                   and (expires_at is null or expires_at > now())
                   and (user_id=? or (? is not null and organization_id=?) or role_code=?)
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

    void updateSecurity(UUID tenantId, UUID documentId, String classification,
                        String discipline, String packageCode, String locationCode) {
        jdbc.update("""
                update documents set security_classification=?, discipline=?, package_code=?, location_code=?, updated_at=now()
                 where tenant_id=? and id=?
                """, classification, discipline, packageCode, locationCode, tenantId, documentId);
    }

    boolean tenantUser(UUID tenantId, UUID userId) {
        if (userId == null) return false;
        Integer count=jdbc.queryForObject("select count(*) from tenant_users where tenant_id=? and id=? and active=true",
                Integer.class,tenantId,userId);
        return count!=null&&count>0;
    }

    boolean activeProjectOrganization(UUID tenantId, UUID projectId, UUID organizationId) {
        if (projectId == null || organizationId == null) return false;
        Integer count=jdbc.queryForObject("""
                select count(*) from project_participants
                 where tenant_id=? and project_id=? and organization_id=? and active=true
                """,Integer.class,tenantId,projectId,organizationId);
        return count!=null&&count>0;
    }

    void insertGrant(UUID tenantId, UUID documentId, UUID userId, UUID organizationId, String roleCode,
                     String permission, UUID grantedBy, LocalDateTime expiresAt) {
        jdbc.update("""
                insert into document_access_grants(tenant_id,document_id,user_id,organization_id,role_code,permission_code,granted_by,expires_at)
                values(?,?,?,?,?,?,?,?)
                """,tenantId,documentId,userId,organizationId,roleCode,permission,grantedBy,expiresAt);
    }

    record DocumentSecurity(UUID projectId, UUID originatorOrganizationId, String classification) {}
}
