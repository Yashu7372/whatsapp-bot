package com.whatsappbot.project;

import com.whatsappbot.auth.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProjectCapabilityAdminService {
    private static final Set<String> EFFECTS=Set.of("ALLOW","DENY");
    private static final Set<String> SCOPES=Set.of("PROJECT","ORGANIZATION","ASSIGNED");
    private final ProjectAccessService accessService;
    private final ProjectCapabilityRepository repository;
    private final PermissionAuditService audit;

    @Transactional(readOnly=true)
    public List<ProjectCapabilityRepository.MatrixRow> list(UUID tenantId,UUID userId,UUID projectId){
        accessService.requireProjectAdministrator(accessService.requireActiveUser(tenantId,userId));
        return repository.list(tenantId,projectId);
    }

    @Transactional
    public void set(UUID tenantId,UUID userId,UUID projectId,ChangeRequest req){
        var actor=accessService.requireActiveUser(tenantId,userId);
        accessService.requireProjectAdministrator(actor);
        PartyRole party=parseParty(req.partyRole());
        UserRole role=parseRole(req.userRole());
        ProjectPermission permission=parsePermission(req.permissionCode());
        String effect=req.effect()==null?"":req.effect().trim().toUpperCase();
        if(!EFFECTS.contains(effect)) throw bad("effect must be ALLOW or DENY");
        String scope=req.dataScope()==null||req.dataScope().isBlank()?null:req.dataScope().trim().toUpperCase();
        if(scope!=null&&!SCOPES.contains(scope)) throw bad("Unsupported dataScope: "+scope);
        if("DENY".equals(effect)) scope=null;
        var old=repository.find(tenantId,projectId,party,role.name(),permission);
        repository.upsert(tenantId,projectId,party,role.name(),permission,effect,scope,userId);
        audit.record(tenantId,projectId,null,userId,"CAPABILITY_OVERRIDE_CHANGED","PARTY_ROLE+USER_ROLE",
                party+"/"+role,permission.name(),old==null?null:Map.of("effect",old.effect(),"dataScope",String.valueOf(old.dataScope())),
                Map.of("effect",effect,"dataScope",String.valueOf(scope)));
    }

    @Transactional
    public void reset(UUID tenantId,UUID userId,UUID projectId,String partyRole,String userRole,String permissionCode){
        var actor=accessService.requireActiveUser(tenantId,userId);
        accessService.requireProjectAdministrator(actor);
        PartyRole party=parseParty(partyRole);UserRole role=parseRole(userRole);ProjectPermission permission=parsePermission(permissionCode);
        var old=repository.find(tenantId,projectId,party,role.name(),permission);
        repository.delete(tenantId,projectId,party,role.name(),permission);
        audit.record(tenantId,projectId,null,userId,"CAPABILITY_OVERRIDE_RESET","PARTY_ROLE+USER_ROLE",
                party+"/"+role,permission.name(),old==null?null:Map.of("effect",old.effect(),"dataScope",String.valueOf(old.dataScope())),null);
    }

    private static PartyRole parseParty(String s){try{return PartyRole.valueOf(s.trim().toUpperCase());}catch(Exception e){throw bad("Invalid partyRole");}}
    private static UserRole parseRole(String s){try{return UserRole.valueOf(s.trim().toUpperCase());}catch(Exception e){throw bad("Invalid userRole");}}
    private static ProjectPermission parsePermission(String s){try{return ProjectPermission.valueOf(s.trim().toUpperCase());}catch(Exception e){throw bad("Invalid permissionCode");}}
    private static ResponseStatusException bad(String s){return new ResponseStatusException(HttpStatus.BAD_REQUEST,s);}
    public record ChangeRequest(String partyRole,String userRole,String permissionCode,String effect,String dataScope){}
}
