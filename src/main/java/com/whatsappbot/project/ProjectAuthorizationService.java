package com.whatsappbot.project;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Central capability + scope policy for multi-company projects.
 *
 * <p>A single project id is intentionally shared by client, consultant, contractor and
 * subcontractors. Authorization therefore returns not just ALLOW/DENY, but the data scope that
 * the caller is allowed to see. Services must apply the returned organization id to commercial
 * queries when the scope is ORGANIZATION.
 */
@Service
@RequiredArgsConstructor
public class ProjectAuthorizationService {

    private final ProjectAccessService accessService;

    public enum DataScope { PROJECT, ORGANIZATION, ASSIGNED }

    public record Decision(TenantUserEntity actor, DataScope scope, UUID organizationId,
                           List<PartyRole> partyRoles) {}

    @Transactional(readOnly = true)
    public Decision require(UUID tenantId, UUID userId, UUID projectId, ProjectPermission permission) {
        TenantUserEntity actor = accessService.requireActiveUser(tenantId, userId);
        accessService.requireProjectVisibility(tenantId, projectId, actor);

        if (accessService.isTenantAdministrator(actor)) {
            return new Decision(actor, DataScope.PROJECT, null, List.of());
        }

        UUID orgId = actor.getOrganizationId();
        if (orgId == null) deny(permission);
        List<PartyRole> roles = accessService.rolesOnProject(tenantId, projectId, actor);
        if (roles.isEmpty()) deny(permission);

        boolean manager = actor.getRole() == UserRole.MANAGER || actor.getRole() == UserRole.ADMIN;
        boolean reviewer = manager || actor.getRole() == UserRole.REVIEWER;
        boolean client = roles.contains(PartyRole.CLIENT);
        boolean consultant = roles.contains(PartyRole.CONSULTANT);
        boolean contractor = roles.contains(PartyRole.CONTRACTOR) || roles.contains(PartyRole.SUBCONTRACTOR);

        return switch (permission) {
            case PROJECT_VIEW, DOCUMENT_VIEW, TRANSMITTAL_VIEW ->
                    new Decision(actor, DataScope.PROJECT, orgId, roles);

            case DOCUMENT_CREATE, DOCUMENT_EDIT, DOCUMENT_ISSUE,
                 TRANSMITTAL_CREATE, TRANSMITTAL_ISSUE -> manager
                    ? new Decision(actor, DataScope.ORGANIZATION, orgId, roles)
                    : deny(permission);

            case DOCUMENT_APPROVE -> reviewer && (client || consultant)
                    ? new Decision(actor, DataScope.ASSIGNED, orgId, roles)
                    : deny(permission);

            case TRANSMITTAL_ACKNOWLEDGE -> reviewer
                    ? new Decision(actor, DataScope.ORGANIZATION, orgId, roles)
                    : deny(permission);

            case COMMERCIAL_VIEW_PROJECT, PAYMENT_VIEW_PROJECT, BUDGET_EDIT_PROJECT ->
                    manager && (client || consultant)
                            ? new Decision(actor, DataScope.PROJECT, orgId, roles)
                            : deny(permission);

            case COMMERCIAL_VIEW_ORGANIZATION, PAYMENT_VIEW_ORGANIZATION,
                 FORECAST_SUBMIT_ORGANIZATION -> manager && (client || consultant || contractor)
                    ? new Decision(actor, DataScope.ORGANIZATION, orgId, roles)
                    : deny(permission);

            case PAYMENT_CREATE_ORGANIZATION -> manager && contractor
                    ? new Decision(actor, DataScope.ORGANIZATION, orgId, roles)
                    : deny(permission);

            case PAYMENT_CERTIFY -> manager && (client || consultant)
                    ? new Decision(actor, DataScope.PROJECT, orgId, roles)
                    : deny(permission);

            case PAYMENT_MARK_PAID -> manager && client
                    ? new Decision(actor, DataScope.PROJECT, orgId, roles)
                    : deny(permission);
        };
    }

    private static Decision deny(ProjectPermission permission) {
        throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Permission " + permission + " is not allowed for this user on this project");
    }
}
