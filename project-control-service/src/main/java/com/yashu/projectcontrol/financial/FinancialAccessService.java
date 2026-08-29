package com.yashu.projectcontrol.financial;

import com.yashu.projectcontrol.access.ActorContext;
import com.yashu.projectcontrol.access.ProjectAccessService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.PROJECT_MANAGE;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.PROJECT_VIEW;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.SCOPE_VIEW;

/**
 * Financial authorization stays relationship based and separate from controllers.
 * Internal organization cost is private by default; contract facts are shared only
 * with the contract parties and authorized project administration.
 */
@Service
public class FinancialAccessService {

    private final ProjectAccessService projectAccessService;

    public FinancialAccessService(ProjectAccessService projectAccessService) {
        this.projectAccessService = projectAccessService;
    }

    public ActorContext requireProjectRead(UUID userId, UUID projectId) {
        return projectAccessService.require(userId, PROJECT_VIEW, projectId, null);
    }

    public ActorContext requireProjectManage(UUID userId, UUID projectId) {
        return projectAccessService.require(userId, PROJECT_MANAGE, projectId, null);
    }

    public ActorContext requirePrivateOrganizationCost(
            UUID userId,
            UUID projectId,
            UUID owningOrganizationId,
            UUID scopeId,
            boolean write) {
        if (owningOrganizationId == null) {
            return write ? requireProjectManage(userId, projectId) : requireProjectRead(userId, projectId);
        }

        ActorContext actor = scopeId == null
                ? requireProjectRead(userId, projectId)
                : projectAccessService.require(userId, SCOPE_VIEW, projectId, scopeId);
        if (!represents(actor, owningOrganizationId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Organization-private financial data is visible only to actors who represent that organization in this project");
        }

        if (write && scopeId != null && !canContributeToScope(actor)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Writing organization-private cost for a scope requires an actionable scope assignment");
        }
        return actor;
    }

    public ActorContext requireContractView(
            UUID userId,
            UUID projectId,
            UUID payerOrganizationId,
            UUID payeeOrganizationId) {
        ActorContext actor = requireProjectRead(userId, projectId);
        if (actor.workspaceRoles().contains("PROJECT_ADMIN")
                || represents(actor, payerOrganizationId)
                || represents(actor, payeeOrganizationId)) {
            return actor;
        }
        throw new ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Contract commercial truth is visible only to an authorized project administrator or one of the contract parties");
    }

    public ActorContext requireContractParty(
            UUID userId,
            UUID projectId,
            UUID organizationId,
            String action) {
        ActorContext actor = requireProjectRead(userId, projectId);
        if (!represents(actor, organizationId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    action + " requires the actor to represent the relevant contract party in this project");
        }
        return actor;
    }

    private static boolean represents(ActorContext actor, UUID organizationId) {
        boolean member = actor.organizationMemberships().stream()
                .anyMatch(membership -> membership.organizationId().equals(organizationId));
        boolean participant = actor.projectParticipations().stream()
                .anyMatch(participation -> participation.organizationId().equals(organizationId));
        return member && participant;
    }

    private static boolean canContributeToScope(ActorContext actor) {
        return actor.workspaceRoles().contains("PROJECT_ADMIN")
                || actor.scopeAssignments().stream().anyMatch(assignment ->
                assignment.accessLevel().equals("CONTRIBUTE")
                        || assignment.accessLevel().equals("MANAGE")
                        || assignment.accessLevel().equals("APPROVE"));
    }
}
