package com.whatsappbot.project;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class ProjectCapabilityRepository {
    private final JdbcTemplate jdbc;

    OverrideRow find(UUID tenantId, UUID projectId, PartyRole partyRole, String userRole, ProjectPermission permission){
        return jdbc.query("""
            select id,effect,data_scope from project_capability_overrides
             where tenant_id=? and project_id=? and party_role=? and user_role=? and permission_code=?
            """, rs->rs.next()?new OverrideRow(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3)):null,
                tenantId,projectId,partyRole.name(),userRole,permission.name());
    }

    List<MatrixRow> list(UUID tenantId, UUID projectId){
        return jdbc.query("""
            select id,party_role,user_role,permission_code,effect,data_scope,updated_at
              from project_capability_overrides where tenant_id=? and project_id=?
             order by party_role,user_role,permission_code
            """,(rs,n)->new MatrixRow(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),
                rs.getString(5),rs.getString(6),rs.getTimestamp(7).toLocalDateTime()),tenantId,projectId);
    }

    void upsert(UUID tenantId,UUID projectId,PartyRole partyRole,String userRole,ProjectPermission permission,
                String effect,String scope,UUID actor){
        jdbc.update("""
            insert into project_capability_overrides(tenant_id,project_id,party_role,user_role,permission_code,effect,data_scope,created_by,updated_by)
            values(?,?,?,?,?,?,?,?,?)
            on conflict(project_id,party_role,user_role,permission_code) do update
               set effect=excluded.effect,data_scope=excluded.data_scope,updated_by=excluded.updated_by,updated_at=now()
            """,tenantId,projectId,partyRole.name(),userRole,permission.name(),effect,scope,actor,actor);
    }

    int delete(UUID tenantId,UUID projectId,PartyRole partyRole,String userRole,ProjectPermission permission){
        return jdbc.update("delete from project_capability_overrides where tenant_id=? and project_id=? and party_role=? and user_role=? and permission_code=?",
                tenantId,projectId,partyRole.name(),userRole,permission.name());
    }

    record OverrideRow(UUID id,String effect,String dataScope){}
    record MatrixRow(UUID id,String partyRole,String userRole,String permissionCode,String effect,String dataScope,LocalDateTime updatedAt){}
}
