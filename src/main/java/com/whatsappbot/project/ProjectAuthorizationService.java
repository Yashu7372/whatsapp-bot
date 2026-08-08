package com.whatsappbot.project;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Central capability + scope policy for multi-company projects. */
@Service
@RequiredArgsConstructor
public class ProjectAuthorizationService {
    private final ProjectAccessService accessService;
    private final ProjectCapabilityRepository capabilityRepository;

    public enum DataScope { PROJECT, ORGANIZATION, ASSIGNED }
    public record Decision(TenantUserEntity actor, DataScope scope, UUID organizationId,List<PartyRole> partyRoles) {}

    @Transactional(readOnly=true)
    public Decision require(UUID tenantId,UUID userId,UUID projectId,ProjectPermission permission){
        TenantUserEntity actor=accessService.requireActiveUser(tenantId,userId);
        accessService.requireProjectVisibility(tenantId,projectId,actor);
        if(accessService.isTenantAdministrator(actor)) return new Decision(actor,DataScope.PROJECT,null,List.of());

        UUID orgId=actor.getOrganizationId();
        if(orgId==null) return deny(permission);
        List<PartyRole> roles=accessService.rolesOnProject(tenantId,projectId,actor);
        if(roles.isEmpty()) return deny(permission);

        // Project-specific matrix overrides are evaluated before the safe platform default.
        // Explicit DENY wins across multiple party roles. Otherwise the widest explicit ALLOW
        // assigned by a tenant administrator is used. No override means the default below.
        List<ProjectCapabilityRepository.OverrideRow> overrides=new ArrayList<>();
        for(PartyRole party:roles){
            var row=capabilityRepository.find(tenantId,projectId,party,actor.getRole().name(),permission);
            if(row!=null){
                if("DENY".equals(row.effect())) return deny(permission);
                overrides.add(row);
            }
        }
        if(!overrides.isEmpty()){
            DataScope scope=overrides.stream().map(r->scope(r.dataScope())).max((a,b)->Integer.compare(rank(a),rank(b))).orElse(DataScope.ASSIGNED);
            return new Decision(actor,scope,orgId,roles);
        }

        boolean manager=actor.getRole()==UserRole.MANAGER||actor.getRole()==UserRole.ADMIN;
        boolean reviewer=manager||actor.getRole()==UserRole.REVIEWER;
        boolean client=roles.contains(PartyRole.CLIENT);
        boolean consultant=roles.contains(PartyRole.CONSULTANT);
        boolean contractor=roles.contains(PartyRole.CONTRACTOR)||roles.contains(PartyRole.SUBCONTRACTOR);

        return switch(permission){
            case PROJECT_VIEW,DOCUMENT_VIEW,TRANSMITTAL_VIEW -> new Decision(actor,DataScope.PROJECT,orgId,roles);
            case DOCUMENT_CREATE,DOCUMENT_EDIT,DOCUMENT_ISSUE,TRANSMITTAL_CREATE,TRANSMITTAL_ISSUE -> manager?new Decision(actor,DataScope.ORGANIZATION,orgId,roles):deny(permission);
            case DOCUMENT_APPROVE -> reviewer&&(client||consultant)?new Decision(actor,DataScope.ASSIGNED,orgId,roles):deny(permission);
            case DOCUMENT_REVIEW_INTERNAL -> reviewer&&contractor?new Decision(actor,DataScope.ASSIGNED,orgId,roles):deny(permission);
            case DOCUMENT_REVIEW_TECHNICAL -> reviewer&&consultant?new Decision(actor,DataScope.ASSIGNED,orgId,roles):deny(permission);
            case DOCUMENT_APPROVE_CLIENT -> reviewer&&client?new Decision(actor,DataScope.ASSIGNED,orgId,roles):deny(permission);
            case DOCUMENT_CERTIFY_COMMERCIAL -> manager&&(client||consultant)?new Decision(actor,DataScope.ASSIGNED,orgId,roles):deny(permission);
            case TRANSMITTAL_ACKNOWLEDGE -> reviewer?new Decision(actor,DataScope.ORGANIZATION,orgId,roles):deny(permission);
            case COMMERCIAL_VIEW_PROJECT,PAYMENT_VIEW_PROJECT,BUDGET_EDIT_PROJECT -> manager&&(client||consultant)?new Decision(actor,DataScope.PROJECT,orgId,roles):deny(permission);
            case COMMERCIAL_VIEW_ORGANIZATION,PAYMENT_VIEW_ORGANIZATION,FORECAST_SUBMIT_ORGANIZATION -> manager&&(client||consultant||contractor)?new Decision(actor,DataScope.ORGANIZATION,orgId,roles):deny(permission);
            case PAYMENT_CREATE_ORGANIZATION -> manager&&contractor?new Decision(actor,DataScope.ORGANIZATION,orgId,roles):deny(permission);
            case PAYMENT_CERTIFY -> manager&&(client||consultant)?new Decision(actor,DataScope.PROJECT,orgId,roles):deny(permission);
            case PAYMENT_MARK_PAID -> manager&&client?new Decision(actor,DataScope.PROJECT,orgId,roles):deny(permission);
        };
    }

    private static DataScope scope(String value){return value==null?DataScope.ASSIGNED:DataScope.valueOf(value);}
    private static int rank(DataScope s){return switch(s){case ASSIGNED->1;case ORGANIZATION->2;case PROJECT->3;};}
    private static Decision deny(ProjectPermission p){throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Permission "+p+" is not allowed for this user on this project");}
}
