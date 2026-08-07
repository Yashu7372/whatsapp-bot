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
 * <p>Two questions have to be asked together, and answering only the first is what left the
 * project and payment endpoints open: what the user's <em>tenant role</em> permits (ADMIN,
 * MANAGER, REVIEWER, VIEWER), and what their <em>company's part in the project</em> permits
 * (client, consultant, contractor, subcontractor). Belonging to the tenant says nothing about
 * being entitled to certify a contractor's money.
 */
@Service
@RequiredArgsConstructor
public class ProjectAccessService {

    /** Tenant roles allowed to configure projects and act across companies. */
    private static final Set<UserRole> TENANT_ADMIN_ROLES = Set.of(UserRole.ADMIN, UserRole.MANAGER);

    private final TenantUserRepository userRepository;
    private final ProjectParticipantRepository participantRepository;

    /** Loads the acting user, confirming they belong to this tenant and are still active. */
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

    /** True for users who administer the tenant itself rather than acting for one company. */
    public boolean isTenantAdministrator(TenantUserEntity user) {
        return TENANT_ADMIN_ROLES.contains(user.getRole());
    }

    /**
     * Guards project configuration — creating projects, changing contract value or retention,
     * moving companies on and off, defining numbering series.
     *
     * <p>Previously these took only a tenant id, so a read-only user could rewrite a project's
     * commercial terms.
     */
    public void requireProjectAdministrator(TenantUserEntity user) {
        if (!isTenantAdministrator(user)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Only an administrator or manager can configure projects");
        }
    }

    /** The company the user acts for, or null for tenant staff not attached to one. */
    public UUID organizationOf(TenantUserEntity user) {
        return user.getOrganizationId();
    }

    /**
     * The capacities the user's company holds on a project. Empty when the user has no company
     * or that company is not engaged here.
     */
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

    /**
     * Requires that the user's company holds one of the given capacities on the project.
     *
     * <p>Tenant administrators pass regardless: somebody has to be able to act when a
     * participant's own staff cannot, and their actions are recorded in the audit trail.
     */
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

    /**
     * Requires that the user may see a project at all — their company is engaged on it, or they
     * administer the tenant.
     *
     * <p>This is what makes the project the sharing boundary in practice. Without it, one
     * tenant's contractor could read another project's documents purely because both projects
     * are hosted in the same tenant account.
     */
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

    /** Non-throwing form of {@link #requireProjectVisibility}, for filtering lists. */
    @Transactional(readOnly = true)
    public boolean canSeeProject(UUID tenantId, UUID projectId, TenantUserEntity user) {
        if (isTenantAdministrator(user)) {
            return true;
        }
        UUID orgId = user.getOrganizationId();
        return orgId != null && participantRepository
                .existsByTenantIdAndProjectIdAndOrganizationIdAndActiveTrue(tenantId, projectId, orgId);
    }

    /** Requires the user to act for the given company, or to administer the tenant. */
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
