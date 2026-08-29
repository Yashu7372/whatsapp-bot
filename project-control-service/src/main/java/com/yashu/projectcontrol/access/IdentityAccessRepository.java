package com.yashu.projectcontrol.access;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
class IdentityAccessRepository {

    private final JdbcTemplate jdbc;

    IdentityAccessRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    UserRow createUser(String externalSubject, String email, String displayName, String passwordHash) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into user_accounts(id,external_subject,email,display_name,status,created_at,password_hash)
                values(?,?,?,?, 'ACTIVE', current_timestamp, ?)
                """, id, externalSubject, email, displayName, passwordHash);
        return requireUser(id);
    }

    boolean existsBySubject(String externalSubject) {
        Integer count = jdbc.queryForObject(
                "select count(*) from user_accounts where external_subject=?", Integer.class, externalSubject);
        return count != null && count > 0;
    }

    boolean existsByEmail(String email) {
        if (email == null) return false;
        Integer count = jdbc.queryForObject(
                "select count(*) from user_accounts where lower(email)=lower(?)", Integer.class, email);
        return count != null && count > 0;
    }

    UserRow requireUser(UUID userId) {
        return findUser(userId).orElseThrow();
    }

    Optional<UserRow> findUser(UUID userId) {
        return jdbc.query("""
                select id,external_subject,email,display_name,status,password_hash
                from user_accounts where id=?
                """, (rs, n) -> mapUser(rs), userId).stream().findFirst();
    }

    Optional<UserRow> findUserByEmail(String email) {
        return jdbc.query("""
                select id,external_subject,email,display_name,status,password_hash
                from user_accounts where lower(email)=lower(?)
                """, (rs, n) -> mapUser(rs), email).stream().findFirst();
    }

    Optional<UserRow> findUserBySubject(String externalSubject) {
        return jdbc.query("""
                select id,external_subject,email,display_name,status,password_hash
                from user_accounts where external_subject=?
                """, (rs, n) -> mapUser(rs), externalSubject).stream().findFirst();
    }

    void updatePasswordHash(UUID userId, String passwordHash) {
        jdbc.update("update user_accounts set password_hash=? where id=?", passwordHash, userId);
    }

    WorkspaceMembershipRow addWorkspaceMembership(
            UUID workspaceId, UUID userId, String accessRole, LocalDate validFrom, LocalDate validTo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into workspace_memberships(id,workspace_id,user_id,access_role,status,valid_from,valid_to,created_at)
                values(?,?,?,?, 'ACTIVE',?,?,current_timestamp)
                """, id, workspaceId, userId, accessRole, validFrom, validTo);
        return new WorkspaceMembershipRow(id, workspaceId, accessRole, "ACTIVE", validFrom, validTo);
    }

    OrganizationMembershipRow addOrganizationMembership(
            UUID organizationId, UUID userId, String responsibilityCode, LocalDate validFrom, LocalDate validTo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into organization_memberships(id,organization_id,user_id,responsibility_code,status,valid_from,valid_to,created_at)
                values(?,?,?,?, 'ACTIVE',?,?,current_timestamp)
                """, id, organizationId, userId, responsibilityCode, validFrom, validTo);
        return new OrganizationMembershipRow(id, organizationId, responsibilityCode, "ACTIVE", validFrom, validTo);
    }

    ScopeAssignmentRow addScopeAssignment(
            UUID projectId, UUID scopeId, UUID userId, UUID projectParticipantId,
            String responsibilityCode, String accessLevel, LocalDate validFrom, LocalDate validTo) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into scope_assignments(
                    id,project_id,scope_id,user_id,project_participant_id,responsibility_code,
                    access_level,status,valid_from,valid_to,created_at)
                values(?,?,?,?,?,?,?,'ACTIVE',?,?,current_timestamp)
                """, id, projectId, scopeId, userId, projectParticipantId,
                responsibilityCode, accessLevel, validFrom, validTo);
        return new ScopeAssignmentRow(id, scopeId, projectParticipantId, responsibilityCode,
                accessLevel, "ACTIVE", validFrom, validTo);
    }

    List<WorkspaceMembershipRow> workspaceMemberships(UUID userId, UUID workspaceId) {
        return jdbc.query("""
                select id,workspace_id,access_role,status,valid_from,valid_to
                from workspace_memberships
                where user_id=? and workspace_id=? and status='ACTIVE'
                  and (valid_from is null or valid_from<=current_date)
                  and (valid_to is null or valid_to>=current_date)
                """, (rs, n) -> new WorkspaceMembershipRow(
                rs.getObject("id", UUID.class), rs.getObject("workspace_id", UUID.class),
                rs.getString("access_role"), rs.getString("status"),
                date(rs, "valid_from"), date(rs, "valid_to")), userId, workspaceId);
    }

    List<OrganizationMembershipRow> organizationMemberships(UUID userId) {
        return jdbc.query("""
                select id,organization_id,responsibility_code,status,valid_from,valid_to
                from organization_memberships
                where user_id=? and status='ACTIVE'
                  and (valid_from is null or valid_from<=current_date)
                  and (valid_to is null or valid_to>=current_date)
                order by organization_id
                """, (rs, n) -> new OrganizationMembershipRow(
                rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getString("responsibility_code"), rs.getString("status"),
                date(rs, "valid_from"), date(rs, "valid_to")), userId);
    }

    List<ProjectParticipationRow> projectParticipations(UUID userId, UUID projectId) {
        return jdbc.query("""
                select pp.id,pp.organization_id,pp.party_role,pp.parent_participant_id
                from organization_memberships om
                join project_participants pp on pp.organization_id=om.organization_id
                where om.user_id=? and pp.project_id=?
                  and om.status='ACTIVE' and pp.status='ACTIVE'
                  and (om.valid_from is null or om.valid_from<=current_date)
                  and (om.valid_to is null or om.valid_to>=current_date)
                  and (pp.valid_from is null or pp.valid_from<=current_date)
                  and (pp.valid_to is null or pp.valid_to>=current_date)
                order by pp.organization_id,pp.party_role
                """, (rs, n) -> new ProjectParticipationRow(
                rs.getObject("id", UUID.class), rs.getObject("organization_id", UUID.class),
                rs.getString("party_role"), rs.getObject("parent_participant_id", UUID.class)), userId, projectId);
    }

    List<ScopeAssignmentRow> scopeAssignments(UUID userId, UUID projectId, UUID scopeId) {
        return jdbc.query("""
                select id,scope_id,project_participant_id,responsibility_code,access_level,status,valid_from,valid_to
                from scope_assignments
                where user_id=? and project_id=? and scope_id=? and status='ACTIVE'
                  and (valid_from is null or valid_from<=current_date)
                  and (valid_to is null or valid_to>=current_date)
                order by responsibility_code
                """, (rs, n) -> new ScopeAssignmentRow(
                rs.getObject("id", UUID.class), rs.getObject("scope_id", UUID.class),
                rs.getObject("project_participant_id", UUID.class), rs.getString("responsibility_code"),
                rs.getString("access_level"), rs.getString("status"),
                date(rs, "valid_from"), date(rs, "valid_to")), userId, projectId, scopeId);
    }

    boolean hasOrganizationScopeRelationship(UUID userId, UUID projectId, UUID scopeId) {
        Integer count = jdbc.queryForObject("""
                select count(*)
                from organization_memberships om
                join project_participants pp on pp.organization_id=om.organization_id
                join scope_participants sp on sp.project_participant_id=pp.id and sp.scope_id=?
                where om.user_id=? and pp.project_id=?
                  and om.status='ACTIVE' and pp.status='ACTIVE' and sp.status='ACTIVE'
                  and (om.valid_from is null or om.valid_from<=current_date)
                  and (om.valid_to is null or om.valid_to>=current_date)
                  and (pp.valid_from is null or pp.valid_from<=current_date)
                  and (pp.valid_to is null or pp.valid_to>=current_date)
                """, Integer.class, scopeId, userId, projectId);
        return count != null && count > 0;
    }

    boolean userBelongsToOrganization(UUID userId, UUID organizationId) {
        Integer count = jdbc.queryForObject("""
                select count(*) from organization_memberships
                where user_id=? and organization_id=? and status='ACTIVE'
                  and (valid_from is null or valid_from<=current_date)
                  and (valid_to is null or valid_to>=current_date)
                """, Integer.class, userId, organizationId);
        return count != null && count > 0;
    }

    private static UserRow mapUser(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new UserRow(
                rs.getObject("id", UUID.class), rs.getString("external_subject"), rs.getString("email"),
                rs.getString("display_name"), rs.getString("status"), rs.getString("password_hash"));
    }

    private static LocalDate date(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    record UserRow(UUID id, String externalSubject, String email, String displayName,
                   String status, String passwordHash) {}
    record WorkspaceMembershipRow(UUID id, UUID workspaceId, String accessRole, String status,
                                  LocalDate validFrom, LocalDate validTo) {}
    record OrganizationMembershipRow(UUID id, UUID organizationId, String responsibilityCode, String status,
                                     LocalDate validFrom, LocalDate validTo) {}
    record ProjectParticipationRow(UUID participantId, UUID organizationId, String partyRole,
                                   UUID parentParticipantId) {}
    record ScopeAssignmentRow(UUID id, UUID scopeId, UUID projectParticipantId, String responsibilityCode,
                              String accessLevel, String status, LocalDate validFrom, LocalDate validTo) {}
}
