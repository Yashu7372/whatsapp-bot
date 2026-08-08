package com.whatsappbot.document;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.UserRole;
import com.whatsappbot.project.PermissionAuditService;
import com.whatsappbot.project.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentSecurityService {
    private static final Set<String> CLASSIFICATIONS=java.util.Arrays.stream(DocumentClassification.values()).map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private static final Set<String> PERMISSIONS=Set.of(DocumentAuthorizationService.VIEW,DocumentAuthorizationService.EDIT,DocumentAuthorizationService.ISSUE,DocumentAuthorizationService.MANAGE);

    private final DocumentAuthorizationRepository repository;
    private final DocumentAuthorizationService authorization;
    private final ProjectAccessService projectAccess;
    private final DocumentAuditService audit;
    private final PermissionAuditService permissionAudit;

    @Transactional
    public void updateSecurity(UUID tenantId,UUID userId,UUID documentId,UpdateSecurityRequest req){
        authorization.requireSecurityAdministration(tenantId,userId,documentId);
        var before=repository.security(tenantId,documentId);
        String classification=normalize(req.classification(),CLASSIFICATIONS,"classification");
        repository.updateSecurity(tenantId,documentId,classification,req.discipline(),req.packageCode(),req.locationCode());
        TenantUserEntity actor=projectAccess.requireActiveUser(tenantId,userId);
        // Restricting a document must not lock its own administrator out of it. A single MANAGE
        // grant is enough now that MANAGE implies VIEW, EDIT and ISSUE.
        if(DocumentClassification.RESTRICTED.name().equals(classification)
                && !repository.hasGrant(tenantId,documentId,userId,actor.getOrganizationId(),actor.getRole().name(),
                        List.of(DocumentAuthorizationService.MANAGE))){
            repository.insertGrant(tenantId,documentId,userId,null,null,DocumentAuthorizationService.MANAGE,userId,null);
        }
        audit.record(tenantId,documentId,userId,DocumentAuditService.SHARE_GRANTED,
                Map.of("action","SECURITY_CLASSIFICATION_CHANGED","classification",classification));
        permissionAudit.record(tenantId,before!=null?before.projectId():null,documentId,userId,"DOCUMENT_SECURITY_CHANGED",
                "DOCUMENT",documentId.toString(),null,
                before==null?null:Map.of("classification",String.valueOf(before.classification())),
                Map.of("classification",classification,"discipline",String.valueOf(req.discipline()),
                        "packageCode",String.valueOf(req.packageCode()),"locationCode",String.valueOf(req.locationCode())));
    }

    @Transactional
    public void grant(UUID tenantId,UUID userId,UUID documentId,GrantRequest req){
        authorization.requireSecurityAdministration(tenantId,userId,documentId);
        String permission=normalize(req.permission(),PERMISSIONS,"permission");
        int principals=(req.userId()!=null?1:0)+(req.organizationId()!=null?1:0)+(req.roleCode()!=null&&!req.roleCode().isBlank()?1:0);
        if(principals!=1)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Exactly one grant principal is required: userId, organizationId or roleCode");
        DocumentAuthorizationRepository.DocumentSecurity doc=repository.security(tenantId,documentId);
        if(req.userId()!=null&&!repository.tenantUser(tenantId,req.userId()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Grant user is not an active member of this tenant");
        if(req.organizationId()!=null){
            if(doc.projectId()==null||!repository.activeProjectOrganization(tenantId,doc.projectId(),req.organizationId()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Grant organization is not an active participant on this project");
            if(!DocumentAuthorizationService.VIEW.equals(permission))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Organization grants are VIEW-only; EDIT/ISSUE/MANAGE must be assigned to a named user");
        }
        String role=req.roleCode()==null?null:req.roleCode().trim().toUpperCase();
        if(role!=null){
            try{UserRole.valueOf(role);}catch(IllegalArgumentException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported roleCode: "+role);}
            if(!DocumentAuthorizationService.VIEW.equals(permission))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Role grants are VIEW-only; EDIT/ISSUE/MANAGE must be assigned to a named user");
        }
        repository.insertGrant(tenantId,documentId,req.userId(),req.organizationId(),role,permission,userId,req.expiresAt());
        String principal=req.userId()!=null?req.userId().toString():req.organizationId()!=null?req.organizationId().toString():role;
        String principalType=req.userId()!=null?"USER":req.organizationId()!=null?"ORGANIZATION":"ROLE";
        audit.record(tenantId,documentId,userId,DocumentAuditService.SHARE_GRANTED,Map.of("permission",permission,"principal",principal));
        permissionAudit.record(tenantId,doc.projectId(),documentId,userId,"DOCUMENT_GRANT_ADDED",principalType,principal,permission,null,
                Map.of("expiresAt",String.valueOf(req.expiresAt())));
    }

    private static String normalize(String value,Set<String> allowed,String field){if(value==null||value.isBlank())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,field+" is required");String n=value.trim().toUpperCase();if(!allowed.contains(n))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported "+field+": "+n);return n;}
    public record UpdateSecurityRequest(String classification,String discipline,String packageCode,String locationCode){}
    public record GrantRequest(UUID userId,UUID organizationId,String roleCode,String permission,LocalDateTime expiresAt){}
}
