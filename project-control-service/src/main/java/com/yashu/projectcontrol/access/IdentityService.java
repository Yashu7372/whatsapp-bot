package com.yashu.projectcontrol.access;

import com.yashu.projectcontrol.organization.OrganizationService;
import com.yashu.projectcontrol.participation.ParticipationService;
import com.yashu.projectcontrol.project.ProjectService;
import com.yashu.projectcontrol.scope.ScopeService;
import com.yashu.projectcontrol.workspace.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

@Service
public class IdentityService {

    private final IdentityAccessRepository repository;
    private final WorkspaceService workspaceService;
    private final OrganizationService organizationService;
    private final ProjectService projectService;
    private final ParticipationService participationService;
    private final ScopeService scopeService;

    public IdentityService(
            IdentityAccessRepository repository,
            WorkspaceService workspaceService,
            OrganizationService organizationService,
            ProjectService projectService,
            ParticipationService participationService,
            ScopeService scopeService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
        this.organizationService = organizationService;
        this.projectService = projectService;
        this.participationService = participationService;
        this.scopeService = scopeService;
    }

    @Transactional
    public UserView createUser(String externalSubject, String email, String displayName) {
        String subject = required(externalSubject, "externalSubject");
        if (repository.existsBySubject(subject)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "User subject already exists: " + subject);
        }
        String name = required(displayName, "displayName");
        String normalizedEmail = email == null || email.isBlank() ? null : email.trim().toLowerCase(Locale.ROOT);
        var row = repository.createUser(subject, normalizedEmail, name);
        return new UserView(row.id(), row.externalSubject(), row.email(), row.displayName(), row.status());
    }

    @Transactional
    public WorkspaceMembershipView addWorkspaceMembership(
            UUID userId, UUID workspaceId, String accessRole, LocalDate validFrom, LocalDate validTo) {
        requireActiveUser(userId);
        workspaceService.requireExists(workspaceId);
        validateDates(validFrom, validTo);
        String role = code(accessRole);
        var row = repository.addWorkspaceMembership(workspaceId, userId, role, validFrom, validTo);
        return new WorkspaceMembershipView(row.id(), row.workspaceId(), role, row.status(), validFrom, validTo);
    }

    @Transactional
    public OrganizationMembershipView addOrganizationMembership(
            UUID userId, UUID organizationId, String responsibilityCode,
            LocalDate validFrom, LocalDate validTo) {
        requireActiveUser(userId);
        organizationService.requireExists(organizationId);
        validateDates(validFrom, validTo);
        String responsibility = code(responsibilityCode);
        var row = repository.addOrganizationMembership(
                organizationId, userId, responsibility, validFrom, validTo);
        return new OrganizationMembershipView(
                row.id(), row.organizationId(), responsibility, row.status(), validFrom, validTo);
    }

    @Transactional
    public ScopeAssignmentView addScopeAssignment(
            UUID userId,
            UUID projectId,
            UUID scopeId,
            UUID projectParticipantId,
            String responsibilityCode,
            String accessLevel,
            LocalDate validFrom,
            LocalDate validTo) {
        requireActiveUser(userId);
        projectService.requireExists(projectId);
        scopeService.requireExistsInProject(projectId, scopeId);
        var participant = participationService.get(projectId, projectParticipantId);
        if (!repository.userBelongsToOrganization(userId, participant.organizationId())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "User cannot be assigned through a project participant for an organization they do not belong to");
        }
        validateDates(validFrom, validTo);
        String responsibility = code(responsibilityCode);
        String level = code(accessLevel);
        if (!level.equals("VIEW") && !level.equals("CONTRIBUTE")
                && !level.equals("MANAGE") && !level.equals("APPROVE")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported scope access level: " + level);
        }
        var row = repository.addScopeAssignment(
                projectId, scopeId, userId, projectParticipantId,
                responsibility, level, validFrom, validTo);
        return new ScopeAssignmentView(
                row.id(), projectId, row.scopeId(), row.projectParticipantId(),
                responsibility, level, row.status(), validFrom, validTo);
    }

    @Transactional(readOnly = true)
    public UserView getUser(UUID userId) {
        var row = requireActiveUser(userId);
        return new UserView(row.id(), row.externalSubject(), row.email(), row.displayName(), row.status());
    }

    IdentityAccessRepository.UserRow requireActiveUser(UUID userId) {
        var row = repository.findUser(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found: " + userId));
        if (!"ACTIVE".equals(row.status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "User is not active");
        }
        return row;
    }

    private static void validateDates(LocalDate validFrom, LocalDate validTo) {
        if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "validTo cannot be before validFrom");
        }
    }

    private static String code(String value) {
        return required(value, "code").toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        return value.trim();
    }

    public record UserView(UUID id, String externalSubject, String email, String displayName, String status) {}

    public record WorkspaceMembershipView(
            UUID id, UUID workspaceId, String accessRole, String status,
            LocalDate validFrom, LocalDate validTo) {}

    public record OrganizationMembershipView(
            UUID id, UUID organizationId, String responsibilityCode, String status,
            LocalDate validFrom, LocalDate validTo) {}

    public record ScopeAssignmentView(
            UUID id, UUID projectId, UUID scopeId, UUID projectParticipantId,
            String responsibilityCode, String accessLevel, String status,
            LocalDate validFrom, LocalDate validTo) {}
}
