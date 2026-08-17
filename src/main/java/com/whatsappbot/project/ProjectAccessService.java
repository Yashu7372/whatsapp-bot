package com.whatsappbot.project;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.TenantUserRepository;
import com.whatsappbot.auth.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The single place that answers "is this person allowed to do this here".
 *
 * <p>Tenant role and project-party role are separate dimensions. A company manager is not a
 * tenant-wide administrator simply because their user role is MANAGER: if they are attached to
 * an organization, every commercial/document decision remains constrained by that organization.
 */
@Service
@RequiredArgsConstructor
public class ProjectAccessService {

    private static final Set<UserRole> TENANT_ADMIN_ROLES = Set.of(UserRole.ADMIN, UserRole.MANAGER);

    private final TenantUserRepository userRepository;
    private final ProjectParticipantRepository participantRepository;

    @Transactional(readOnly = true)
    public TenantUserEntity requireActiveUser(UUID tenantId, UUID userId) {
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No acting user on this request");
        }
        return userRepository.findById(userId)
                .filter(u -> u.getTenant().getId().equals(tenantId))
                .filter(TenantUserEntity::isActive)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "User is not an active member of this tenant"));
    }

    /**
     * True only for tenant/platform staff that are not acting for a participating company.
     * Organization-attached ADMIN/MANAGER users remain organization-scoped.
     */
    public boolean isTenantAdministrator(TenantUserEntity user) {
        return user != null
                && user.getOrganizationId() == null
                && TENANT_ADMIN_ROLES.contains(user.getRole());
    }

    public void requireProjectAdministrator(TenantUserEntity user) {
        if (!isTenantAdministrator(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only a tenant administrator can configure project-wide settings");
        }
    }

    public UUID organizationOf(TenantUserEntity user) {
        return user.getOrganizationId();
    }

    @Transactional(readOnly = true)
    public List<PartyRole> rolesOnProject(UUID tenantId, UUID projectId, TenantUserEntity user) {
        UUID orgId = user.getOrganizationId();
        if (orgId == null) {
            return List.of();
        }
        return participantRepository
                .findAllByTenantIdAndProjectIdAndActiveTrueOrderByPartyRoleAsc(tenantId, projectId)
                .stream()
                .filter(p -> p.getOrganization().getId().equals(orgId))
                .map(ProjectParticipantEntity::getPartyRole)
                .toList();
    }

    @Transactional(readOnly = true)
    public void requirePartyRole(UUID tenantId, UUID projectId, TenantUserEntity user,
                                 PartyRole... allowed) {
        if (isTenantAdministrator(user)) {
            return;
        }
        List<PartyRole> held = rolesOnProject(tenantId, projectId, user);
        boolean permitted = Arrays.stream(allowed).anyMatch(held::contains);
        if (!permitted) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your organization is not acting as " + Arrays.toString(allowed) + " on this project");
        }
    }

    @Transactional(readOnly = true)
    public void requireProjectVisibility(UUID tenantId, UUID projectId, TenantUserEntity user) {
        if (isTenantAdministrator(user)) {
            return;
        }
        UUID orgId = user.getOrganizationId();
        if (orgId == null || !participantRepository
                .existsByTenantIdAndProjectIdAndOrganizationIdAndActiveTrue(tenantId, projectId, orgId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Your organization is not a participant on this project");
        }
    }

    @Transactional(readOnly = true)
    public boolean canSeeProject(UUID tenantId, UUID projectId, TenantUserEntity user) {
        if (isTenantAdministrator(user)) {
            return true;
        }
        UUID orgId = user.getOrganizationId();
        return orgId != null && participantRepository
                .existsByTenantIdAndProjectIdAndOrganizationIdAndActiveTrue(tenantId, projectId, orgId);
    }

    public void requireOwnOrganization(TenantUserEntity user, UUID organizationId, String action) {
        if (isTenantAdministrator(user)) {
            return;
        }
        if (user.getOrganizationId() == null || !user.getOrganizationId().equals(organizationId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only the owning organization can " + action);
        }
    }
}
