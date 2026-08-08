package com.whatsappbot.project;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PermissionAuditService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    @Transactional
    public void record(UUID tenantId,UUID projectId,UUID documentId,UUID actorId,String eventType,
                       String principalType,String principalValue,String permission,Map<String,Object> oldValue,Map<String,Object> newValue){
        jdbc.update("""
            insert into permission_audit_events(tenant_id,project_id,document_id,actor_user_id,event_type,
                principal_type,principal_value,permission_code,old_value,new_value)
            values(?,?,?,?,?,?,?,?,?::jsonb,?::jsonb)
            """,tenantId,projectId,documentId,actorId,eventType,principalType,principalValue,permission,json(oldValue),json(newValue));
    }

    @Transactional(readOnly=true)
    public List<AuditView> list(UUID tenantId,UUID projectId){
        return jdbc.query("""
            select e.id,e.project_id,e.document_id,e.event_type,e.principal_type,e.principal_value,
                   e.permission_code,e.old_value::text,e.new_value::text,e.created_at,u.email
              from permission_audit_events e left join tenant_users u on u.id=e.actor_user_id
             where e.tenant_id=? and (? is null or e.project_id=?) order by e.created_at desc limit 500
            """,(rs,n)->new AuditView(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),
                rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),
                rs.getTimestamp(10).toLocalDateTime(),rs.getString(11)),tenantId,projectId,projectId);
    }

    private String json(Map<String,Object> value){
        if(value==null) return null;
        try{return mapper.writeValueAsString(value);}catch(JsonProcessingException e){throw new IllegalStateException(e);}
    }

    public record AuditView(UUID id,UUID projectId,UUID documentId,String eventType,String principalType,
                            String principalValue,String permissionCode,String oldValue,String newValue,
                            LocalDateTime createdAt,String actorEmail){}
}
