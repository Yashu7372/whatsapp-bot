package com.yashu.projectcontrol.access;

import java.util.List;
import java.util.UUID;

/**
 * Resolved business access context for one user inside one project/scope request.
 * Authentication proves who the caller is; this object explains the caller's
 * business relationships inside Project Control.
 */
public record ActorContext(
        UUID userId,
        UUID workspaceId,
        UUID projectId,
        UUID scopeId,
        List<String> workspaceRoles,
        List<OrganizationMembership> organizationMemberships,
        List<ProjectParticipation> projectParticipations,
        List<ScopeAssignment> scopeAssignments,
        boolean organizationAssignedToScope) {

    public record OrganizationMembership(UUID organizationId, String responsibilityCode) {}

    public record ProjectParticipation(
            UUID participantId,
            UUID organizationId,
            String partyRole,
            UUID parentParticipantId) {}

    public record ScopeAssignment(
            UUID assignmentId,
            UUID scopeId,
            UUID projectParticipantId,
            String responsibilityCode,
            String accessLevel) {}
}
