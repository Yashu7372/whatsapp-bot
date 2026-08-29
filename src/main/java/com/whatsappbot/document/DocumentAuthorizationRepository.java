package com.whatsappbot.document;

import com.whatsappbot.auth.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class DocumentAuthorizationRepository {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;

    DocumentSecurity security(UUID tenantId, UUID documentId) {
        return jdbc.query("""
                select project_id, originator_org_id, security_classification, created_by
                  from documents
                 where tenant_id=? and id=?
                """, rs -> rs.next()
                ? new DocumentSecurity(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getString(3), rs.getObject(4, UUID.class))
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

    /**
     * True when the actor holds any explicit grant that satisfies the requested permission.
     * Named parameters deliberately keep the Java arguments tied to their business meaning instead
     * of relying on positional placeholder counts.
     */
    boolean hasGrant(UUID tenantId, UUID documentId, UUID userId, UUID organizationId,
                     String roleCode, List<String> acceptedPermissions) {
        if (acceptedPermissions.isEmpty()) return false;

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("documentId", documentId)
                .addValue("permissions", acceptedPermissions)
                .addValue("userId", userId)
                .addValue("organizationId", organizationId)
                .addValue("roleCode", roleCode);

        Integer count = namedJdbc.queryForObject("""
                select count(*)
                  from document_access_grants
                 where tenant_id=:tenantId
                   and document_id=:documentId
                   and permission_code in (:permissions)
                   and (expires_at is null or expires_at > now())
                   and (
                        user_id=:userId
                        or organization_id=:organizationId
                        or role_code=:roleCode
                   )
                """, params, Integer.class);
        return count != null && count > 0;
    }

    /**
     * Returns the ids of documents the actor may read, newest first, one page at a time.
     *
     * <p>Document content is intentionally narrower than project visibility. Entering a project does
     * not reveal every document in it. A business document is visible when the actor is a manager
     * within the applicable project scope, created the document, holds an explicit VIEW-implying
     * grant, or is assigned to the currently active workflow step. Tenant ADMIN is a system role and
     * is deliberately excluded from business-document content.
     */
    List<UUID> visibleDocumentIds(UUID tenantId, boolean tenantAdministrator, UUID userId, UUID organizationId,
                                  String roleCode, String email, String docType, int limit, int offset) {
        if (UserRole.ADMIN.name().equals(roleCode)) return List.of();

        boolean manager = UserRole.MANAGER.name().equals(roleCode);
        boolean tenantManager = tenantAdministrator && manager;
        List<String> viewGrants = List.of(DocumentAuthorizationService.VIEW, DocumentAuthorizationService.EDIT,
                DocumentAuthorizationService.ISSUE, DocumentAuthorizationService.MANAGE);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("docType", docType)
                .addValue("tenantManager", tenantManager)
                .addValue("manager", manager)
                .addValue("viewGrants", viewGrants)
                .addValue("userId", userId)
                .addValue("organizationId", organizationId)
                .addValue("roleCode", roleCode)
                .addValue("email", email)
                .addValue("limit", limit)
                .addValue("offset", offset);

        return namedJdbc.query("""
                select d.id
                  from documents d
                 where d.tenant_id=:tenantId
                   and (cast(:docType as text) is null or d.doc_type=:docType)
                   and (
                        d.project_id is null
                        or :tenantManager
                        or exists (
                            select 1
                              from project_participants scope_pp
                             where scope_pp.tenant_id=d.tenant_id
                               and scope_pp.project_id=d.project_id
                               and scope_pp.organization_id=:organizationId
                               and scope_pp.active=true
                        )
                   )
                   and (
                        :manager
                        or d.created_by=:userId
                        or exists (
                            select 1
                              from document_access_grants g
                             where g.tenant_id=d.tenant_id
                               and g.document_id=d.id
                               and g.permission_code in (:viewGrants)
                               and (g.expires_at is null or g.expires_at > now())
                               and (
                                    g.user_id=:userId
                                    or g.organization_id=:organizationId
                                    or g.role_code=:roleCode
                               )
                        )
                        or exists (
                            select 1
                              from document_approvals a
                              join document_approval_steps current_s
                                on current_s.approval_id=a.id
                               and current_s.step_index=a.current_step
                              join document_approval_steps s
                                on s.approval_id=a.id
                             where a.tenant_id=d.tenant_id
                               and a.document_id=d.id
                               and a.status='PENDING'
                               and s.decision is null
                               and (
                                    s.step_index=a.current_step
                                    or (current_s.parallel_group is not null
                                        and s.parallel_group=current_s.parallel_group)
                               )
                               and (
                                    (s.assignment_type='USER'
                                        and lower(s.reviewer_email)=lower(:email))
                                    or (s.assignment_type='ORGANIZATION'
                                        and s.assignment_organization_id=:organizationId)
                                    or (s.assignment_type='PARTY_ROLE' and exists (
                                        select 1
                                          from project_participants assigned_pp
                                         where assigned_pp.tenant_id=d.tenant_id
                                           and assigned_pp.project_id=d.project_id
                                           and assigned_pp.organization_id=:organizationId
                                           and assigned_pp.party_role=s.assignment_party_role
                                           and assigned_pp.active=true
                                    ))
                               )
                        )
                   )
                 order by d.updated_at desc
                 limit :limit offset :offset
                """, params, (rs, n) -> rs.getObject(1, UUID.class));
    }

    /**
     * True only when the actor is assigned to the workflow step that is active now (or another
     * active step in the same parallel group). Future reviewers do not gain document visibility
     * before the workflow reaches them.
     */
    boolean assignedToApproval(UUID tenantId, UUID documentId, String reviewerEmail, UUID organizationId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("tenantId", tenantId)
                .addValue("documentId", documentId)
                .addValue("reviewerEmail", reviewerEmail)
                .addValue("organizationId", organizationId);

        Integer count = namedJdbc.queryForObject("""
                select count(*)
                  from document_approvals a
                  join documents d on d.id=a.document_id
                  join document_approval_steps current_s
                    on current_s.approval_id=a.id
                   and current_s.step_index=a.current_step
                  join document_approval_steps s
                    on s.approval_id=a.id
                 where a.tenant_id=:tenantId
                   and a.document_id=:documentId
                   and a.status='PENDING'
                   and s.decision is null
                   and (
                        s.step_index=a.current_step
                        or (current_s.parallel_group is not null
                            and s.parallel_group=current_s.parallel_group)
                   )
                   and (
                        (s.assignment_type='USER'
                            and lower(s.reviewer_email)=lower(:reviewerEmail))
                        or (s.assignment_type='ORGANIZATION'
                            and s.assignment_organization_id=:organizationId)
                        or (s.assignment_type='PARTY_ROLE' and exists (
                            select 1
                              from project_participants pp
                             where pp.tenant_id=a.tenant_id
                               and pp.project_id=d.project_id
                               and pp.organization_id=:organizationId
                               and pp.party_role=s.assignment_party_role
                               and pp.active=true
                        ))
                   )
                """, params, Integer.class);
        return count != null && count > 0;
    }

    void updateSecurity(UUID tenantId,UUID documentId,String classification,String discipline,String packageCode,String locationCode){
        jdbc.update("""
            update documents set security_classification=?,discipline=?,package_code=?,location_code=?,updated_at=now()
             where tenant_id=? and id=?
            """,classification,discipline,packageCode,locationCode,tenantId,documentId);
    }

    void insertGrant(UUID tenantId,UUID documentId,UUID userId,UUID organizationId,String roleCode,
                     String permission,UUID grantedBy,LocalDateTime expiresAt){
        jdbc.update("""
            insert into document_access_grants(tenant_id,document_id,user_id,organization_id,role_code,permission_code,granted_by,expires_at)
            values(?,?,?,?,?,?,?,?)
            """,tenantId,documentId,userId,organizationId,roleCode,permission,grantedBy,expiresAt);
    }

    boolean tenantUser(UUID tenantId,UUID userId){
        Integer n=jdbc.queryForObject("select count(*) from tenant_users where tenant_id=? and id=? and active=true",Integer.class,tenantId,userId);
        return n!=null&&n>0;
    }

    boolean activeProjectOrganization(UUID tenantId,UUID projectId,UUID organizationId){
        Integer n=jdbc.queryForObject("select count(*) from project_participants where tenant_id=? and project_id=? and organization_id=? and active=true",Integer.class,tenantId,projectId,organizationId);
        return n!=null&&n>0;
    }

    record DocumentSecurity(UUID projectId, UUID originatorOrganizationId, String classification,
                            UUID createdByUserId) {}
    record ApprovalAuthority(UUID documentId, UUID projectId, UUID originatorOrganizationId,
                             String authorityType, String assignmentType, UUID assignmentOrganizationId,
                             String assignmentPartyRole, String reviewerEmail) {}
}
