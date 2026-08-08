package com.whatsappbot.document;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
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

    /**
     * True when the actor holds any grant that satisfies the requested permission.
     *
     * <p>{@code acceptedPermissions} carries the implication set — a stored EDIT grant satisfies a
     * VIEW request — so a grantee cannot end up able to modify a document they are refused
     * permission to read.
     */
    boolean hasGrant(UUID tenantId, UUID documentId, UUID userId, UUID organizationId,
                     String roleCode, List<String> acceptedPermissions) {
        if (acceptedPermissions.isEmpty()) return false;
        Object[] args = new Object[3 + acceptedPermissions.size() + 4];
        int i = 0;
        args[i++] = tenantId;
        args[i++] = documentId;
        for (String permission : acceptedPermissions) args[i++] = permission;
        args[i++] = userId;
        args[i++] = organizationId;
        args[i++] = organizationId;
        args[i] = roleCode;

        Integer count = jdbc.queryForObject("""
                select count(*)
                  from document_access_grants
                 where tenant_id=? and document_id=? and permission_code in %s
                   and (expires_at is null or expires_at > now())
                   and (
                        user_id=?
                        or (?::uuid is not null and organization_id=?)
                        or role_code=?
                   )
                """.formatted(placeholders(acceptedPermissions.size())), Integer.class, args);
        return count != null && count > 0;
    }

    static String placeholders(int size) {
        return "(" + String.join(",", java.util.Collections.nCopies(size, "?")) + ")";
    }

    /**
     * Returns the ids of documents the actor may read, newest first, one page at a time.
     *
     * <p>The register previously loaded every document in the tenant and then asked an
     * authorization service about each one in turn — roughly six queries per row, with no page
     * limit at all. These predicates are the same rules {@code DocumentAuthorizationService}
     * applies to a single document, rewritten so the database answers once.
     *
     * <p>The classification arms collapse deliberately. Per-document the rules read as
     * PROJECT: any participant; ORGANIZATION: owner, grant or assignment; RESTRICTED: grant or
     * assignment. Because a grant or an assignment satisfies both of the latter two, the whole
     * test is equivalent to "PROJECT, or owner of an ORGANIZATION document, or granted, or
     * assigned" — which lets the grant and assignment sub-queries appear exactly once each.
     */
    List<UUID> visibleDocumentIds(UUID tenantId, boolean tenantAdministrator, UUID userId, UUID organizationId,
                                  String roleCode, String email, String docType, int limit, int offset) {
        List<String> viewGrants = List.of(DocumentAuthorizationService.VIEW, DocumentAuthorizationService.EDIT,
                DocumentAuthorizationService.ISSUE, DocumentAuthorizationService.MANAGE);

        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(docType);
        args.add(docType);
        args.add(tenantAdministrator);
        // tenant-level (no project) arm: grant principals then assigned reviewer
        args.addAll(viewGrants);
        args.add(userId);
        args.add(organizationId);
        args.add(organizationId);
        args.add(roleCode);
        args.add(email);
        // project arm
        args.add(organizationId);
        args.add(organizationId);
        args.addAll(viewGrants);
        args.add(userId);
        args.add(organizationId);
        args.add(organizationId);
        args.add(roleCode);
        args.add(email);
        args.add(organizationId);
        args.add(organizationId);
        args.add(limit);
        args.add(offset);

        return jdbc.query("""
                select d.id from documents d
                 where d.tenant_id=?
                   and (?::text is null or d.doc_type=?)
                   and (
                        ?::boolean
                     or (d.project_id is null and (
                             d.security_classification='PROJECT'
                          or exists (select 1 from document_access_grants tg
                                      where tg.tenant_id=d.tenant_id and tg.document_id=d.id
                                        and tg.permission_code in %s
                                        and (tg.expires_at is null or tg.expires_at > now())
                                        and (tg.user_id=? or (?::uuid is not null and tg.organization_id=?) or tg.role_code=?))
                          or exists (select 1 from document_approval_steps ts
                                       join document_approvals ta on ta.id=ts.approval_id
                                      where ta.tenant_id=d.tenant_id and ta.document_id=d.id
                                        and ta.status='PENDING' and ts.decision is null
                                        and ts.assignment_type='USER' and lower(ts.reviewer_email)=lower(?))
                        ))
                     or (
                          exists (select 1 from project_participants pp
                                   where pp.tenant_id=d.tenant_id and pp.project_id=d.project_id
                                     and pp.organization_id=? and pp.active=true)
                          and (
                               d.security_classification='PROJECT'
                            or (d.security_classification='ORGANIZATION' and d.originator_org_id=?)
                            or exists (select 1 from document_access_grants g
                                        where g.tenant_id=d.tenant_id and g.document_id=d.id
                                          and g.permission_code in %s
                                          and (g.expires_at is null or g.expires_at > now())
                                          and (g.user_id=? or (?::uuid is not null and g.organization_id=?) or g.role_code=?))
                            or exists (select 1 from document_approval_steps s
                                         join document_approvals a on a.id=s.approval_id
                                        where a.tenant_id=d.tenant_id and a.document_id=d.id
                                          and a.status='PENDING' and s.decision is null
                                          and (
                                               (s.assignment_type='USER' and lower(s.reviewer_email)=lower(?))
                                            or (s.assignment_type='ORGANIZATION' and s.assignment_organization_id=?)
                                            or (s.assignment_type='PARTY_ROLE' and exists (
                                                   select 1 from project_participants pp2
                                                    where pp2.tenant_id=d.tenant_id and pp2.project_id=d.project_id
                                                      and pp2.organization_id=? and pp2.party_role=s.assignment_party_role
                                                      and pp2.active=true))
                                          ))
                          )
                        )
                   )
                 order by d.updated_at desc
                 limit ? offset ?
                """.formatted(placeholders(viewGrants.size()), placeholders(viewGrants.size())),
                (rs, n) -> rs.getObject(1, UUID.class), args.toArray());
    }

    /**
     * True when a pending workflow stage is assigned to this actor.
     *
     * <p>This used to match only on reviewer_email, i.e. USER assignments. A stage assigned to a
     * company or to a party role therefore conferred no read access, so a reviewer could be
     * required to decide a restricted document that every read endpoint refused to show them —
     * approving blind. Company and party-role assignments now count, which is what the workflow
     * meant by assigning them.
     */
    boolean assignedToApproval(UUID tenantId, UUID documentId, String reviewerEmail, UUID organizationId) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                  from document_approval_steps s
                  join document_approvals a on a.id=s.approval_id
                  join documents d on d.id=a.document_id
                 where a.tenant_id=? and a.document_id=? and a.status='PENDING' and s.decision is null
                   and (
                        (s.assignment_type='USER' and ?::text is not null and lower(s.reviewer_email)=lower(?))
                     or (s.assignment_type='ORGANIZATION' and ?::uuid is not null and s.assignment_organization_id=?)
                     or (s.assignment_type='PARTY_ROLE' and ?::uuid is not null and exists (
                            select 1 from project_participants pp
                             where pp.tenant_id=a.tenant_id and pp.project_id=d.project_id
                               and pp.organization_id=? and pp.party_role=s.assignment_party_role
                               and pp.active=true))
                   )
                """, Integer.class, tenantId, documentId,
                reviewerEmail, reviewerEmail,
                organizationId, organizationId,
                organizationId, organizationId);
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

    record DocumentSecurity(UUID projectId, UUID originatorOrganizationId, String classification) {}
    record ApprovalAuthority(UUID documentId, UUID projectId, UUID originatorOrganizationId,
                             String authorityType, String assignmentType, UUID assignmentOrganizationId,
                             String assignmentPartyRole, String reviewerEmail) {}
}
