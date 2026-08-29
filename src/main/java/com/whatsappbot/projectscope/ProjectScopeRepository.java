package com.whatsappbot.projectscope;

import lombok.RequiredArgsConstructor;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class ProjectScopeRepository {

    private final JdbcTemplate jdbc;

    public List<ScopeTypeRow> listTypes(UUID tenantId) {
        return jdbc.query("""
                select id,code,name,category,schema_version,configuration_schema_json::text,status
                from project_scope_types
                where tenant_id=? and status='ACTIVE'
                order by category,code
                """, (rs, n) -> new ScopeTypeRow(
                rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                rs.getString("category"), rs.getInt("schema_version"),
                rs.getString("configuration_schema_json"), rs.getString("status")), tenantId);
    }

    public Optional<ScopeTypeRow> findType(UUID tenantId, UUID typeId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject("""
                    select id,code,name,category,schema_version,configuration_schema_json::text,status
                    from project_scope_types where tenant_id=? and id=?
                    """, (rs, n) -> new ScopeTypeRow(
                    rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("name"),
                    rs.getString("category"), rs.getInt("schema_version"),
                    rs.getString("configuration_schema_json"), rs.getString("status")), tenantId, typeId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public List<ScopeRow> listScopes(UUID tenantId, UUID projectId) {
        return jdbc.query(scopeSelect() + " where s.tenant_id=? and s.project_id=? order by s.sort_order,s.code,s.name",
                scopeMapper(), tenantId, projectId);
    }

    public Optional<ScopeRow> findScope(UUID tenantId, UUID projectId, UUID scopeId) {
        try {
            return Optional.ofNullable(jdbc.queryForObject(scopeSelect()
                            + " where s.tenant_id=? and s.project_id=? and s.id=?",
                    scopeMapper(), tenantId, projectId, scopeId));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public boolean scopeExists(UUID tenantId, UUID projectId, UUID scopeId) {
        Integer count = jdbc.queryForObject("select count(*) from project_scopes where tenant_id=? and project_id=? and id=?",
                Integer.class, tenantId, projectId, scopeId);
        return count != null && count > 0;
    }

    public boolean organizationExists(UUID tenantId, UUID organizationId) {
        Integer count = jdbc.queryForObject("select count(*) from organizations where tenant_id=? and id=? and active=true",
                Integer.class, tenantId, organizationId);
        return count != null && count > 0;
    }

    public ScopeRow insert(UUID id, UUID tenantId, UUID projectId, UUID parentScopeId, UUID typeId,
                           String code, String name, String description, UUID ownerOrganizationId,
                           String status, LocalDate plannedStart, LocalDate plannedFinish,
                           LocalDate actualStart, LocalDate actualFinish, int sortOrder,
                           String configurationJson) {
        jdbc.update("""
                insert into project_scopes(
                    id,tenant_id,project_id,parent_scope_id,scope_type_id,code,name,description,
                    owner_organization_id,status,planned_start,planned_finish,actual_start,actual_finish,
                    sort_order,configuration_json,version,created_at,updated_at)
                values(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,cast(? as jsonb),0,now(),now())
                """, id, tenantId, projectId, parentScopeId, typeId, code, name, description,
                ownerOrganizationId, status, plannedStart, plannedFinish, actualStart, actualFinish,
                sortOrder, configurationJson);
        return findScope(tenantId, projectId, id).orElseThrow();
    }

    public boolean update(UUID tenantId, UUID projectId, UUID scopeId, long expectedVersion,
                          UUID parentScopeId, UUID typeId, String code, String name, String description,
                          UUID ownerOrganizationId, String status, LocalDate plannedStart,
                          LocalDate plannedFinish, LocalDate actualStart, LocalDate actualFinish,
                          int sortOrder, String configurationJson) {
        return jdbc.update("""
                update project_scopes
                set parent_scope_id=?,scope_type_id=?,code=?,name=?,description=?,owner_organization_id=?,
                    status=?,planned_start=?,planned_finish=?,actual_start=?,actual_finish=?,sort_order=?,
                    configuration_json=cast(? as jsonb),version=version+1,updated_at=now()
                where tenant_id=? and project_id=? and id=? and version=?
                """, parentScopeId, typeId, code, name, description, ownerOrganizationId, status,
                plannedStart, plannedFinish, actualStart, actualFinish, sortOrder, configurationJson,
                tenantId, projectId, scopeId, expectedVersion) == 1;
    }

    public boolean wouldCreateCycle(UUID tenantId, UUID projectId, UUID scopeId, UUID proposedParentId) {
        if (proposedParentId == null) {
            return false;
        }
        Integer count = jdbc.queryForObject("""
                with recursive descendants as (
                    select id from project_scopes where tenant_id=? and project_id=? and id=?
                    union all
                    select s.id from project_scopes s join descendants d on s.parent_scope_id=d.id
                    where s.tenant_id=? and s.project_id=?
                )
                select count(*) from descendants where id=?
                """, Integer.class, tenantId, projectId, scopeId, tenantId, projectId, proposedParentId);
        return count != null && count > 0;
    }

    public List<CapabilityRow> listCapabilities(UUID tenantId, UUID projectId, UUID scopeId) {
        return jdbc.query("""
                select capability_code,mode,configuration_json::text,status
                from scope_capability_bindings
                where tenant_id=? and project_id=? and scope_id=? and status='ACTIVE'
                order by capability_code
                """, (rs, n) -> new CapabilityRow(rs.getString("capability_code"), rs.getString("mode"),
                rs.getString("configuration_json"), rs.getString("status")), tenantId, projectId, scopeId);
    }

    public CapabilityRow putCapability(UUID tenantId, UUID projectId, UUID scopeId, String capabilityCode,
                                       String mode, String configurationJson) {
        jdbc.update("""
                insert into scope_capability_bindings(
                    tenant_id,project_id,scope_id,capability_code,mode,configuration_json,status,created_at,updated_at)
                values(?,?,?,?,?,cast(? as jsonb),'ACTIVE',now(),now())
                on conflict(scope_id,capability_code) do update
                set mode=excluded.mode,configuration_json=excluded.configuration_json,status='ACTIVE',updated_at=now()
                """, tenantId, projectId, scopeId, capabilityCode, mode, configurationJson);
        return jdbc.queryForObject("""
                select capability_code,mode,configuration_json::text,status
                from scope_capability_bindings
                where tenant_id=? and project_id=? and scope_id=? and capability_code=?
                """, (rs, n) -> new CapabilityRow(rs.getString("capability_code"), rs.getString("mode"),
                rs.getString("configuration_json"), rs.getString("status")), tenantId, projectId, scopeId, capabilityCode);
    }

    private String scopeSelect() {
        return """
                select s.id,s.tenant_id,s.project_id,s.parent_scope_id,s.scope_type_id,t.code scope_type_code,
                       t.name scope_type_name,t.category scope_category,s.code,s.name,s.description,
                       s.owner_organization_id,s.status,s.planned_start,s.planned_finish,s.actual_start,s.actual_finish,
                       s.sort_order,s.configuration_json::text,s.version
                from project_scopes s join project_scope_types t on t.id=s.scope_type_id and t.tenant_id=s.tenant_id
                """;
    }

    private org.springframework.jdbc.core.RowMapper<ScopeRow> scopeMapper() {
        return (rs, n) -> new ScopeRow(
                rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class),
                rs.getObject("project_id", UUID.class), rs.getObject("parent_scope_id", UUID.class),
                rs.getObject("scope_type_id", UUID.class), rs.getString("scope_type_code"),
                rs.getString("scope_type_name"), rs.getString("scope_category"), rs.getString("code"),
                rs.getString("name"), rs.getString("description"), rs.getObject("owner_organization_id", UUID.class),
                rs.getString("status"), date(rs, "planned_start"), date(rs, "planned_finish"),
                date(rs, "actual_start"), date(rs, "actual_finish"), rs.getInt("sort_order"),
                rs.getString("configuration_json"), rs.getLong("version"));
    }

    private static LocalDate date(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        java.sql.Date value = rs.getDate(column);
        return value == null ? null : value.toLocalDate();
    }

    public record ScopeTypeRow(UUID id, String code, String name, String category, int schemaVersion,
                               String configurationSchemaJson, String status) {}

    public record ScopeRow(UUID id, UUID tenantId, UUID projectId, UUID parentScopeId, UUID scopeTypeId,
                           String scopeTypeCode, String scopeTypeName, String scopeCategory, String code,
                           String name, String description, UUID ownerOrganizationId, String status,
                           LocalDate plannedStart, LocalDate plannedFinish, LocalDate actualStart,
                           LocalDate actualFinish, int sortOrder, String configurationJson, long version) {}

    public record CapabilityRow(String capabilityCode, String mode, String configurationJson, String status) {}
}
