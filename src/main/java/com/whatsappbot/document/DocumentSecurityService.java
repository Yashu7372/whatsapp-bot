package com.whatsappbot.document;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.UserRole;
import com.whatsappbot.project.ProjectAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class DocumentSecurityService {
    private static final Set<String> CLASSIFICATIONS=Set.of("PROJECT","ORGANIZATION","RESTRICTED");
    private static final Set<String> PERMISSIONS=Set.of("VIEW","EDIT","ISSUE");

    private final DocumentAuthorizationRepository repository;
    private final DocumentAuthorizationService authorization;
    private final ProjectAccessService projectAccess;
    private final DocumentAuditService audit;

    @Transactional
    public void updateSecurity(UUID tenantId, UUID userId, UUID documentId, UpdateSecurityRequest req){
        authorization.requireEdit(tenantId,userId,documentId);
        String classification=normalize(req.classification(),CLASSIFICATIONS,"classification");
        repository.updateSecurity(tenantId,documentId,classification,req.discipline(),req.packageCode(),req.locationCode());

        // Prevent accidental lockout when the originator changes a document to RESTRICTED.
        TenantUserEntity actor=projectAccess.requireActiveUser(tenantId,userId);
        if("RESTRICTED".equals(classification)){
            for(String permission:PERMISSIONS){
                if(!repository.hasGrant(tenantId,documentId,userId,actor.getOrganizationId(),actor.getRole().name(),permission))
                    repository.insertGrant(tenantId,documentId,userId,null,null,permission,userId,null);
            }
        }
        audit.record(tenantId,documentId,userId,DocumentAuditService.SHARE_GRANTED,
                Map.of("action","SECURITY_CLASSIFICATION_CHANGED","classification",classification));
    }

    @Transactional
    public void grant(UUID tenantId, UUID userId, UUID documentId, GrantRequest req){
        authorization.requireEdit(tenantId,userId,documentId);
        String permission=normalize(req.permission(),PERMISSIONS,"permission");
        int principals=(req.userId()!=null?1:0)+(req.organizationId()!=null?1:0)+(req.roleCode()!=null&&!req.roleCode().isBlank()?1:0);
        if(principals!=1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Exactly one grant principal is required: userId, organizationId or roleCode");

        DocumentAuthorizationRepository.DocumentSecurity doc=repository.security(tenantId,documentId);
        if(req.userId()!=null && !repository.tenantUser(tenantId,req.userId()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Grant user is not an active member of this tenant");
        if(req.organizationId()!=null){
            if(doc.projectId()==null || !repository.activeProjectOrganization(tenantId,doc.projectId(),req.organizationId()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Grant organization is not an active participant on this project");
            if(!"VIEW".equals(permission))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Organization grants are VIEW-only; EDIT/ISSUE must be assigned to a named user");
        }
        String role=req.roleCode()==null?null:req.roleCode().trim().toUpperCase();
        if(role!=null){
            try{UserRole.valueOf(role);}catch(IllegalArgumentException ex){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported roleCode: "+role);}
            if(!"VIEW".equals(permission))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Role grants are VIEW-only; EDIT/ISSUE must be assigned to a named user");
        }

        repository.insertGrant(tenantId,documentId,req.userId(),req.organizationId(),role,permission,userId,req.expiresAt());
        audit.record(tenantId,documentId,userId,DocumentAuditService.SHARE_GRANTED,
                Map.of("permission",permission,"principal",req.userId()!=null?req.userId().toString():req.organizationId()!=null?req.organizationId().toString():role));
    }

    private static String normalize(String value,Set<String> allowed,String field){
        if(value==null||value.isBlank()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,field+" is required");
        String normalized=value.trim().toUpperCase();
        if(!allowed.contains(normalized)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Unsupported "+field+": "+normalized);
        return normalized;
    }

    public record UpdateSecurityRequest(String classification,String discipline,String packageCode,String locationCode){}
    public record GrantRequest(UUID userId,UUID organizationId,String roleCode,String permission,LocalDateTime expiresAt){}
}
