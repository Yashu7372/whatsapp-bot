package com.yashu.projectcontrol.access;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
class WorkflowConfigurationOptionRepository {

    private final JdbcTemplate jdbc;

    WorkflowConfigurationOptionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    List<AssignmentOptionRow> scopeAssignmentOptions(UUID projectId, UUID scopeId) {
        return jdbc.query("""
                select distinct sa.responsibility_code, sa.access_level, pp.party_role
                from scope_assignments sa
                join project_participants pp on pp.id = sa.project_participant_id
                where sa.project_id=? and sa.scope_id=?
                  and sa.status='ACTIVE' and pp.status='ACTIVE'
                  and (sa.valid_from is null or sa.valid_from<=current_date)
                  and (sa.valid_to is null or sa.valid_to>=current_date)
                  and (pp.valid_from is null or pp.valid_from<=current_date)
                  and (pp.valid_to is null or pp.valid_to>=current_date)
                order by sa.responsibility_code, pp.party_role, sa.access_level
                """, (rs, n) -> new AssignmentOptionRow(
                rs.getString("responsibility_code"),
                rs.getString("access_level"),
                rs.getString("party_role")), projectId, scopeId);
    }

    record AssignmentOptionRow(String responsibilityCode, String accessLevel, String partyRole) {}
}
