package com.yashu.projectcontrol.access;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.yashu.projectcontrol.project.ProjectService;
import com.yashu.projectcontrol.scope.ScopeService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Central business authorization choke point for Project Control.
 * Authentication proves who the caller is; this service decides what that actor
 * may do inside a concrete project/scope relationship context.
 */
@Service
public class ProjectAccessService {

    private final IdentityAccessRepository repository;
    private final IdentityService identityService;
    private final ProjectService projectService;
    private final ScopeService scopeService;
    private final ObjectMapper objectMapper;

    public ProjectAccessService(
            IdentityAccessRepository repository,
            IdentityService identityService,
            ProjectService projectService,
            ScopeService scopeService,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.identityService = identityService;
        this.projectService = projectService;
        this.scopeService = scopeService;
        this.objectMapper = objectMapper;
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
        return decideFromContext(resolveActor(userId, projectId, scopeId), action);
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

    @Transactional(readOnly = true)
    public ActorContext requireCanRepresentOrganization(UUID userId, UUID projectId, UUID organizationId) {
        if (organizationId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "originatorOrganizationId is required");
        }
        ActorContext context = resolveActor(userId, projectId, null);
        if (context.workspaceRoles().contains("PROJECT_ADMIN")) {
            return context;
        }
        boolean member = context.organizationMemberships().stream()
                .anyMatch(membership -> membership.organizationId().equals(organizationId));
        boolean participant = context.projectParticipations().stream()
                .anyMatch(participation -> participation.organizationId().equals(organizationId));
        if (!member || !participant) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Actor cannot submit a document as an organization they do not represent in this project");
        }
        return context;
    }

    @Transactional(readOnly = true)
    public ActorContext requireWorkflowStepAssignment(
            UUID userId, UUID projectId, UUID scopeId, String assignmentJson) {
        ActorContext context = require(userId, AccessAction.WORKFLOW_ACT, projectId, scopeId);
        if (context.workspaceRoles().contains("PROJECT_ADMIN")) {
            return context;
        }
        if (assignmentJson == null || assignmentJson.isBlank() || assignmentJson.trim().equals("{}")) {
            return context;
        }

        final JsonNode rule;
        try {
            rule = objectMapper.readTree(assignmentJson);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Workflow step assignment is not valid JSON", ex);
        }
        if (rule == null || !rule.isObject()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Workflow step assignment must be a JSON object");
        }

        boolean recognized = false;
        Set<String> responsibilities = stringValues(rule, "responsibility", "responsibilityCodes");
        if (!responsibilities.isEmpty()) {
            recognized = true;
            boolean matched = context.scopeAssignments().stream()
                    .map(ActorContext.ScopeAssignment::responsibilityCode)
                    .map(ProjectAccessService::normalize)
                    .anyMatch(responsibilities::contains);
            if (!matched) denyStep("responsibility", responsibilities);
        }

        Set<String> partyRoles = stringValues(rule, "partyRole", "partyRoles");
        if (!partyRoles.isEmpty()) {
            recognized = true;
            boolean matched = context.projectParticipations().stream()
                    .map(ActorContext.ProjectParticipation::partyRole)
                    .map(ProjectAccessService::normalize)
                    .anyMatch(partyRoles::contains);
            if (!matched) denyStep("party role", partyRoles);
        }

        Set<String> accessLevels = stringValues(rule, "accessLevel", "accessLevels");
        if (!accessLevels.isEmpty()) {
            recognized = true;
            boolean matched = context.scopeAssignments().stream()
                    .map(ActorContext.ScopeAssignment::accessLevel)
                    .map(ProjectAccessService::normalize)
                    .anyMatch(accessLevels::contains);
            if (!matched) denyStep("access level", accessLevels);
        }

        Set<String> workspaceRoles = stringValues(rule, "workspaceRole", "workspaceRoles");
        if (!workspaceRoles.isEmpty()) {
            recognized = true;
            boolean matched = context.workspaceRoles().stream()
                    .map(ProjectAccessService::normalize)
                    .anyMatch(workspaceRoles::contains);
            if (!matched) denyStep("workspace role", workspaceRoles);
        }

        Set<UUID> organizationIds = uuidValues(rule, "organizationId", "organizationIds");
        if (!organizationIds.isEmpty()) {
            recognized = true;
            boolean matched = context.organizationMemberships().stream()
                    .map(ActorContext.OrganizationMembership::organizationId)
                    .anyMatch(organizationIds::contains);
            if (!matched) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Current workflow step is assigned to a different organization");
            }
        }

        if (!recognized && rule.size() > 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Workflow step assignment contains no supported assignment criteria");
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
        boolean resourceVisible = actor.scopeId() == null
                ? workspaceViewer || projectParticipant
                : workspaceViewer || scopeRelationship;

        return switch (action) {
            case PROJECT_VIEW -> allowed(workspaceViewer || projectParticipant,
                    "Project visibility requires workspace membership or organization participation");
            case PROJECT_MANAGE -> allowed(workspaceAdmin,
                    "Project configuration requires PROJECT_ADMIN workspace membership");
            case SCOPE_VIEW -> allowed(resourceVisible,
                    "Scope visibility requires workspace access or a scope relationship");
            case SCOPE_MANAGE -> allowed(workspaceAdmin || canManageScope,
                    "Scope management requires PROJECT_ADMIN or a MANAGE/APPROVE scope assignment");
            case DOCUMENT_VIEW, DOCUMENT_CONTENT_VIEW -> allowed(resourceVisible,
                    "Document visibility requires project/scope visibility");
            case DOCUMENT_SUBMIT -> allowed(workspaceAdmin || canContribute,
                    "Document submission requires PROJECT_ADMIN or a CONTRIBUTE/MANAGE/APPROVE scope assignment");
            case WORKFLOW_CONFIGURE -> allowed(workspaceAdmin || canManageScope,
                    "Workflow configuration requires PROJECT_ADMIN or a MANAGE/APPROVE scope assignment");
            case WORKFLOW_START, WORKFLOW_ACT -> allowed(workspaceAdmin || canContribute,
                    "Workflow execution requires PROJECT_ADMIN or an actionable scope assignment");
        };
    }

    private static Set<String> stringValues(JsonNode object, String singleName, String pluralName) {
        Set<String> values = new HashSet<>();
        addStrings(values, object.get(singleName));
        addStrings(values, object.get(pluralName));
        return values;
    }

    private static void addStrings(Set<String> values, JsonNode node) {
        if (node == null || node.isNull()) return;
        if (node.isTextual()) {
            values.add(normalize(node.asText()));
            return;
        }
        if (node.isArray()) {
            node.forEach(value -> {
                if (value.isTextual()) values.add(normalize(value.asText()));
            });
        }
    }

    private static Set<UUID> uuidValues(JsonNode object, String singleName, String pluralName) {
        Set<UUID> values = new HashSet<>();
        addUuids(values, object.get(singleName));
        addUuids(values, object.get(pluralName));
        return values;
    }

    private static void addUuids(Set<UUID> values, JsonNode node) {
        if (node == null || node.isNull()) return;
        if (node.isTextual()) {
            values.add(parseUuid(node.asText()));
            return;
        }
        if (node.isArray()) {
            node.forEach(value -> {
                if (value.isTextual()) values.add(parseUuid(value.asText()));
            });
        }
    }

    private static UUID parseUuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Workflow assignment contains an invalid organization UUID");
        }
    }

    private static String normalize(String value) {
        return value.trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static void denyStep(String criterion, Set<String> expected) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Current workflow step requires " + criterion + " " + expected);
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
        DOCUMENT_VIEW,
        DOCUMENT_SUBMIT,
        DOCUMENT_CONTENT_VIEW,
        WORKFLOW_CONFIGURE,
        WORKFLOW_START,
        WORKFLOW_ACT
    }

    public enum AccessOutcome {
        ALLOW,
        DENY
    }

    public record AccessDecision(AccessOutcome outcome, String reason) {}
}
