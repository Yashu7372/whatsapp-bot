package com.yashu.projectcontrol.access;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PathVariable;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users")
public class IdentityController {

    private final IdentityService identityService;

    public IdentityController(IdentityService identityService) {
        this.identityService = identityService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IdentityService.UserView create(@Valid @RequestBody CreateUserRequest request) {
        return identityService.createUser(request.externalSubject(), request.email(), request.displayName());
    }

    @PostMapping("/{userId}/workspace-memberships")
    @ResponseStatus(HttpStatus.CREATED)
    public IdentityService.WorkspaceMembershipView addWorkspaceMembership(
            @PathVariable UUID userId,
            @Valid @RequestBody WorkspaceMembershipRequest request) {
        return identityService.addWorkspaceMembership(
                userId, request.workspaceId(), request.accessRole(), request.validFrom(), request.validTo());
    }

    @PostMapping("/{userId}/organization-memberships")
    @ResponseStatus(HttpStatus.CREATED)
    public IdentityService.OrganizationMembershipView addOrganizationMembership(
            @PathVariable UUID userId,
            @Valid @RequestBody OrganizationMembershipRequest request) {
        return identityService.addOrganizationMembership(
                userId, request.organizationId(), request.responsibilityCode(),
                request.validFrom(), request.validTo());
    }

    @PostMapping("/{userId}/scope-assignments")
    @ResponseStatus(HttpStatus.CREATED)
    public IdentityService.ScopeAssignmentView addScopeAssignment(
            @PathVariable UUID userId,
            @Valid @RequestBody ScopeAssignmentRequest request) {
        return identityService.addScopeAssignment(
                userId,
                request.projectId(),
                request.scopeId(),
                request.projectParticipantId(),
                request.responsibilityCode(),
                request.accessLevel(),
                request.validFrom(),
                request.validTo());
    }

    public record CreateUserRequest(
            @NotBlank String externalSubject,
            @Email String email,
            @NotBlank String displayName) {}

    public record WorkspaceMembershipRequest(
            @NotNull UUID workspaceId,
            @NotBlank String accessRole,
            LocalDate validFrom,
            LocalDate validTo) {}

    public record OrganizationMembershipRequest(
            @NotNull UUID organizationId,
            @NotBlank String responsibilityCode,
            LocalDate validFrom,
            LocalDate validTo) {}

    public record ScopeAssignmentRequest(
            @NotNull UUID projectId,
            @NotNull UUID scopeId,
            @NotNull UUID projectParticipantId,
            @NotBlank String responsibilityCode,
            @NotBlank String accessLevel,
            LocalDate validFrom,
            LocalDate validTo) {}
}
