package com.whatsappbot.document;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
class DocumentDeliveryRepository {
    private final JdbcTemplate jdbc;

    RevisionRef currentRevision(UUID tenantId, UUID documentId) {
        return jdbc.query("""
                select d.project_id,d.originator_org_id,d.current_version,v.id,v.revision_code,v.issue_status
                  from documents d join document_versions v
                    on v.document_id=d.id and v.version_num=d.current_version
                 where d.tenant_id=? and d.id=?
                """, rs -> rs.next() ? new RevisionRef(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
                rs.getInt(3),rs.getObject(4,UUID.class),rs.getString(5),rs.getString(6)) : null,
                tenantId,documentId);
    }

    void issueCurrentRevision(UUID tenantId, UUID documentId, UUID versionId, UUID actorId, String purpose) {
        jdbc.update("update document_versions set issue_status='SUPERSEDED' where tenant_id=? and document_id=? and issue_status='ISSUED' and id<>?",
                tenantId,documentId,versionId);
        jdbc.update("update document_versions set issue_status='ISSUED',issue_purpose=?,issued_at=now(),issued_by=? where tenant_id=? and id=?",
                purpose,actorId,tenantId,versionId);
        jdbc.update("""
                update documents d set status='PUBLISHED',issue_purpose=?,current_revision_code=v.revision_code,
                       issued_at=now(),issued_by=?,updated_at=now()
                  from document_versions v
                 where d.tenant_id=? and d.id=? and v.id=?
                """,purpose,actorId,tenantId,documentId,versionId);
    }

    boolean activeProjectOrganization(UUID tenantId, UUID projectId, UUID organizationId) {
        Integer n=jdbc.queryForObject("select count(*) from project_participants where tenant_id=? and project_id=? and organization_id=? and active=true",
                Integer.class,tenantId,projectId,organizationId);
        return n!=null && n>0;
    }

    UUID createTransmittal(UUID id, UUID tenantId, UUID projectId, UUID senderOrg, String number,
                           String purpose, String subject, String message, UUID actorId) {
        jdbc.update("""
                insert into document_transmittals(id,tenant_id,project_id,transmittal_no,sender_organization_id,purpose,subject,message,created_by)
                values(?,?,?,?,?,?,?,?,?)
                """,id,tenantId,projectId,number,senderOrg,purpose,subject,message,actorId);
        return id;
    }

    TransmittalOwner transmittalOwner(UUID tenantId, UUID transmittalId) {
        return jdbc.query("select project_id,sender_organization_id,status from document_transmittals where tenant_id=? and id=?",
                rs->rs.next()?new TransmittalOwner(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3)):null,
                tenantId,transmittalId);
    }

    boolean revisionBelongsToProject(UUID tenantId, UUID projectId, UUID documentId, UUID versionId) {
        Integer n=jdbc.queryForObject("""
                select count(*) from documents d join document_versions v on v.document_id=d.id
                 where d.tenant_id=? and d.project_id=? and d.id=? and v.id=? and v.issue_status='ISSUED'
                """,Integer.class,tenantId,projectId,documentId,versionId);
        return n!=null && n>0;
    }

    void addItem(UUID tenantId, UUID transmittalId, UUID documentId, UUID versionId) {
        jdbc.update("insert into document_transmittal_items(tenant_id,transmittal_id,document_id,document_version_id) values(?,?,?,?)",
                tenantId,transmittalId,documentId,versionId);
    }

    void addRecipient(UUID tenantId, UUID transmittalId, UUID organizationId) {
        jdbc.update("insert into document_transmittal_recipients(tenant_id,transmittal_id,recipient_organization_id) values(?,?,?) on conflict do nothing",
                tenantId,transmittalId,organizationId);
    }

    int itemCount(UUID transmittalId) {
        Integer n=jdbc.queryForObject("select count(*) from document_transmittal_items where transmittal_id=?",Integer.class,transmittalId);
        return n==null?0:n;
    }

    int recipientCount(UUID transmittalId) {
        Integer n=jdbc.queryForObject("select count(*) from document_transmittal_recipients where transmittal_id=?",Integer.class,transmittalId);
        return n==null?0:n;
    }

    void issueTransmittal(UUID tenantId, UUID transmittalId, UUID actorId) {
        jdbc.update("update document_transmittals set status='ISSUED',issued_at=now(),issued_by=?,updated_at=now() where tenant_id=? and id=? and status='DRAFT'",
                actorId,tenantId,transmittalId);
    }

    int acknowledge(UUID tenantId, UUID transmittalId, UUID organizationId, UUID actorId) {
        int n=jdbc.update("""
                update document_transmittal_recipients set acknowledged_at=now(),acknowledged_by=?
                 where tenant_id=? and transmittal_id=? and recipient_organization_id=? and acknowledged_at is null
                """,actorId,tenantId,transmittalId,organizationId);
        if(n>0){
            jdbc.update("""
                    update document_transmittals t set status=case
                        when not exists(select 1 from document_transmittal_recipients r where r.transmittal_id=t.id and r.acknowledged_at is null)
                            then 'ACKNOWLEDGED' else 'PARTIALLY_ACKNOWLEDGED' end, updated_at=now()
                     where t.tenant_id=? and t.id=? and t.status in ('ISSUED','PARTIALLY_ACKNOWLEDGED')
                    """,tenantId,transmittalId);
        }
        return n;
    }

    List<TransmittalView> list(UUID tenantId, UUID projectId, UUID organizationId) {
        String scope=organizationId==null?"":" and (t.sender_organization_id=? or exists(select 1 from document_transmittal_recipients r where r.transmittal_id=t.id and r.recipient_organization_id=?))";
        Object[] args=organizationId==null?new Object[]{tenantId,projectId}:new Object[]{tenantId,projectId,organizationId,organizationId};
        return jdbc.query("""
                select t.id,t.transmittal_no,t.sender_organization_id,o.name,t.purpose,t.subject,t.status,t.issued_at,t.created_at,
                       (select count(*) from document_transmittal_items i where i.transmittal_id=t.id),
                       (select count(*) from document_transmittal_recipients r where r.transmittal_id=t.id)
                  from document_transmittals t join organizations o on o.id=t.sender_organization_id
                 where t.tenant_id=? and t.project_id=?
                """+scope+" order by t.created_at desc",(rs,n)->new TransmittalView(rs.getObject(1,UUID.class),rs.getString(2),
                rs.getObject(3,UUID.class),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),
                rs.getTimestamp(8)==null?null:rs.getTimestamp(8).toLocalDateTime(),rs.getTimestamp(9).toLocalDateTime(),rs.getInt(10),rs.getInt(11)),args);
    }

    record RevisionRef(UUID projectId,UUID originatorOrganizationId,int versionNum,UUID versionId,String revisionCode,String issueStatus){}
    record TransmittalOwner(UUID projectId,UUID senderOrganizationId,String status){}
    record TransmittalView(UUID id,String transmittalNo,UUID senderOrganizationId,String senderOrganizationName,String purpose,
                           String subject,String status,LocalDateTime issuedAt,LocalDateTime createdAt,int itemCount,int recipientCount){}
}
