package com.whatsappbot.document;

import com.whatsappbot.project.ProjectAccessService;
import com.whatsappbot.project.ProjectAuthorizationService;
import com.whatsappbot.project.ProjectPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TransmittalDetailService {
    private final JdbcTemplate jdbc;
    private final ProjectAuthorizationService authorization;
    private final ProjectAccessService accessService;

    @Transactional(readOnly=true)
    public Detail get(UUID tenantId,UUID userId,UUID transmittalId){
        Header h=jdbc.query("""
            select t.id,t.project_id,t.transmittal_no,t.sender_organization_id,o.name,t.purpose,t.subject,t.message,
                   t.status,t.created_at,t.issued_at,t.created_by,t.issued_by
              from document_transmittals t join organizations o on o.id=t.sender_organization_id
             where t.tenant_id=? and t.id=?
            """,rs->rs.next()?new Header(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getObject(4,UUID.class),
                rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),ts(rs.getTimestamp(10)),ts(rs.getTimestamp(11)),
                rs.getObject(12,UUID.class),rs.getObject(13,UUID.class)):null,tenantId,transmittalId);
        if(h==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Transmittal not found");
        var decision=authorization.require(tenantId,userId,h.projectId(), ProjectPermission.TRANSMITTAL_VIEW);
        if(!accessService.isTenantAdministrator(decision.actor())&&!visibleToOrganization(transmittalId,decision.organizationId(),h.senderOrganizationId()))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Transmittal is outside your organization scope");

        List<Item> items=jdbc.query("""
            select i.id,i.document_id,i.document_version_id,d.document_code,d.title,v.revision_code,v.issue_purpose,i.created_at
              from document_transmittal_items i join documents d on d.id=i.document_id join document_versions v on v.id=i.document_version_id
             where i.tenant_id=? and i.transmittal_id=? order by i.created_at
            """,(rs,n)->new Item(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),ts(rs.getTimestamp(8))),tenantId,transmittalId);
        List<Recipient> recipients=jdbc.query("""
            select r.id,r.recipient_organization_id,o.name,r.created_at,r.acknowledged_at,u.email
              from document_transmittal_recipients r join organizations o on o.id=r.recipient_organization_id
              left join tenant_users u on u.id=r.acknowledged_by
             where r.tenant_id=? and r.transmittal_id=? order by r.created_at
            """,(rs,n)->new Recipient(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),ts(rs.getTimestamp(4)),ts(rs.getTimestamp(5)),rs.getString(6)),tenantId,transmittalId);

        List<History> history=new ArrayList<>();history.add(new History("CREATED",h.createdAt(),h.senderOrganizationName(),h.transmittalNo()));
        for(Item i:items)history.add(new History("REVISION_ADDED",i.createdAt(),i.documentCode(),i.revisionCode()));
        for(Recipient r:recipients){history.add(new History("RECIPIENT_ADDED",r.createdAt(),r.organizationName(),null));if(r.acknowledgedAt()!=null)history.add(new History("ACKNOWLEDGED",r.acknowledgedAt(),r.organizationName(),r.acknowledgedByEmail()));}
        if(h.issuedAt()!=null)history.add(new History("ISSUED",h.issuedAt(),h.senderOrganizationName(),h.purpose()));
        history.sort(Comparator.comparing(History::at));
        return new Detail(h,items,recipients,history);
    }

    private boolean visibleToOrganization(UUID transmittalId,UUID orgId,UUID sender){
        if(orgId==null)return false;if(orgId.equals(sender))return true;
        Integer n=jdbc.queryForObject("select count(*) from document_transmittal_recipients where transmittal_id=? and recipient_organization_id=?",Integer.class,transmittalId,orgId);
        return n!=null&&n>0;
    }
    private static LocalDateTime ts(java.sql.Timestamp t){return t==null?null:t.toLocalDateTime();}
    public record Header(UUID id,UUID projectId,String transmittalNo,UUID senderOrganizationId,String senderOrganizationName,String purpose,String subject,String message,String status,LocalDateTime createdAt,LocalDateTime issuedAt,UUID createdBy,UUID issuedBy){}
    public record Item(UUID id,UUID documentId,UUID versionId,String documentCode,String title,String revisionCode,String issuePurpose,LocalDateTime createdAt){}
    public record Recipient(UUID id,UUID organizationId,String organizationName,LocalDateTime createdAt,LocalDateTime acknowledgedAt,String acknowledgedByEmail){}
    public record History(String eventType,LocalDateTime at,String subject,String detail){}
    public record Detail(Header header,List<Item> items,List<Recipient> recipients,List<History> history){}
}
