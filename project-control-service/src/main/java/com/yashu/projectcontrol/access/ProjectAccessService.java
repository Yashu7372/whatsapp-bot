package com.yashu.projectcontrol.access;

import com.yashu.projectcontrol.project.ProjectService;
import com.yashu.projectcontrol.scope.ScopeService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Central business authorization choke point for Project Control.
 *
 * <p>This service deliberately evaluates relationship context instead of treating
 * one generic user role as permission to every project/company resource.</p>
 */
@Service
public class ProjectAccessService {

    private final IdentityAccessRepository repository;
    private final IdentityService identityService;
    private final ProjectService projectService;
    private final ScopeService scopeService;

    public ProjectAccessService(
            IdentityAccessRepository repository,
            IdentityService identityService,
            ProjectService projectService,
            ScopeService scopeService) {
        this.repository = repository;
        this.identityService = identityService;
        this.projectService = projectService;
        this.scopeService = scopeService;
    }

    @Transactional(readOnly = true)
    public ActorContext resolveActor(UUID userId, UUID projectId, UUID scopeId) {
        identityService.requireActiveUser(userId);
        var project = projectService.get(projectId);
        if (scopeId != null) {
            scopeService.requireExistsInProject(projectId, scopeId);
        }

        var workspaceRoles = repository.workspaceMemberships(userId, project.workspaceId()).stream()
                .map(IdentityAccessRepository.WorkspaceMembershipRow::accessRole)
                .distinct()
                .toList();
        var organizations = repository.organizationMemberships(userId).stream()
                .map(row -> new ActorContext.OrganizationMembership(
                        row.organizationId(), row.responsibilityCode()))
                .toList();
        var participations = repository.projectParticipations(userId, projectId).stream()
                .map(row -> new ActorContext.ProjectParticipation(
                        row.participantId(), row.organizationId(), row.partyRole(), row.parentParticipantId()))
                .toList();
        var assignments = scopeId == null ? List.<ActorContext.ScopeAssignment>of()
                : repository.scopeAssignments(userId, projectId, scopeId).stream()
                .map(row -> new ActorContext.ScopeAssignment(
                        row.id(), row.scopeId(), row.projectParticipantId(),
                        row.responsibilityCode(), row.accessLevel()))
                .toList();
        boolean organizationAssignedToScope = scopeId != null
                && repository.hasOrganizationScopeRelationship(userId, projectId, scopeId);

        return new ActorContext(
                userId, project.workspaceId(), projectId, scopeId,
                workspaceRoles, organizations, participations, assignments,
                organizationAssignedToScope);
    }

    @Transactional(readOnly = true)
    public AccessDecision decide(UUID userId, AccessAction action, UUID projectId, UUID scopeId) {
        ActorContext actor = resolveActor(userId, projectId, scopeId);
        boolean workspaceAdmin = actor.workspaceRoles().contains("PROJECT_ADMIN");
        boolean workspaceViewer = workspaceAdmin || actor.workspaceRoles().contains("PROJECT_VIEWER");
        boolean projectParticipant = !actor.projectParticipations().isEmpty();
        boolean directScopeAssignment = !actor.scopeAssignments().isEmpty();
        boolean scopeRelationship = directScopeAssignment || actor.organizationAssignedToScope();
        boolean canContribute = actor.scopeAssignments().stream()
                .anyMatch(a -> a.accessLevel().equals("CONTRIBUTE")
                        || a.accessLevel().equals("MANAGE")
                        || a.accessLevel().equals("APPROVE"));
        boolean canManageScope = actor.scopeAssignments().stream()
                .anyMatch(a -> a.accessLevel().equals("MANAGE") || a.accessLevel().equals("APPROVE"));

        return switch (action) {
            case PROJECT_VIEW -> allowed(workspaceViewer || projectParticipant,
                    "Project visibility requires workspace membership or organization participation");
            case PROJECT_MANAGE -> allowed(workspaceAdmin,
                    "Project configuration requires PROJECT_ADMIN workspace membership");
            case SCOPE_VIEW -> allowed(workspaceViewer || scopeRelationship,
                    "Scope visibility requires workspace access or a scope relationship");
            case SCOPE_MANAGE -> allowed(workspaceAdmin || canManageScope,
                    "Scope management requires PROJECT_ADMIN or a MANAGE/APPROVE scope assignment");
            case WORKFLOW_ACT -> allowed(workspaceAdmin || canContribute,
                    "Workflow action requires PROJECT_ADMIN or an actionable scope assignment");
        };
    }

    @Transactional(readOnly = true)
    public ActorContext require(UUID userId, AccessAction action, UUID projectId, UUID scopeId) {
        ActorContext context = resolveActor(userId, projectId, scopeId);
        AccessDecision decision = decideFromContext(context, action);
        if (decision.outcome() != AccessOutcome.ALLOW) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, decision.reason());
        }
        return context;
    }

    private AccessDecision decideFromContext(ActorContext actor, AccessAction action) {
        boolean workspaceAdmin = actor.workspaceRoles().contains("PROJECT_ADMIN");
        boolean workspaceViewer = workspaceAdmin || actor.workspaceRoles().contains("PROJECT_VIEWER");
        boolean projectParticipant = !actor.projectParticipations().isEmpty();
        boolean scopeRelationship = actor.organizationAssignedToScope() || !actor.scopeAssignments().isEmpty();
        boolean canContribute = actor.scopeAssignments().stream()
                .anyMatch(a -> a.accessLevel().equals("CONTRIBUTE")
                        || a.accessLevel().equals("MANAGE")
                        || a.accessLevel().equals("APPROVE"));
        boolean canManageScope = actor.scopeAssignments().stream()
                .anyMatch(a -> a.accessLevel().equals("MANAGE") || a.accessLevel().equals("APPROVE"));

        return switch (action) {
            case PROJECT_VIEW -> allowed(workspaceViewer || projectParticipant,
                    "Project visibility requires workspace membership or organization participation");
            case PROJECT_MANAGE -> allowed(workspaceAdmin,
                    "Project configuration requires PROJECT_ADMIN workspace membership");
            case SCOPE_VIEW -> allowed(workspaceViewer || scopeRelationship,
                    "Scope visibility requires workspace access or a scope relationship");
            case SCOPE_MANAGE -> allowed(workspaceAdmin || canManageScope,
                    "Scope management requires PROJECT_ADMIN or a MANAGE/APPROVE scope assignment");
            case WORKFLOW_ACT -> allowed(workspaceAdmin || canContribute,
                    "Workflow action requires PROJECT_ADMIN or an actionable scope assignment");
        };
    }

    private static AccessDecision allowed(boolean allowed, String denialReason) {
        return allowed
                ? new AccessDecision(AccessOutcome.ALLOW, "Allowed by resolved project relationship context")
                : new AccessDecision(AccessOutcome.DENY, denialReason);
    }

    public enum AccessAction {
        PROJECT_VIEW,
        PROJECT_MANAGE,
        SCOPE_VIEW,
        SCOPE_MANAGE,
        WORKFLOW_ACT
    }

    public enum AccessOutcome {
        ALLOW,
        DENY
    }

    public record AccessDecision(AccessOutcome outcome, String reason) {}
}
