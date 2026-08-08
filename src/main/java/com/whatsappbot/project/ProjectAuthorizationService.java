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

/** Central capability + scope policy for multi-company projects. */
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
        if (orgId == null) return deny(permission);
        List<PartyRole> roles = accessService.rolesOnProject(tenantId, projectId, actor);
        if (roles.isEmpty()) return deny(permission);

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

            // Legacy generic approval remains limited to parties administering the project.
            case DOCUMENT_APPROVE -> reviewer && (client || consultant)
                    ? new Decision(actor, DataScope.ASSIGNED, orgId, roles)
                    : deny(permission);

            // Internal review belongs to the delivery organization itself.
            case DOCUMENT_REVIEW_INTERNAL -> reviewer && contractor
                    ? new Decision(actor, DataScope.ASSIGNED, orgId, roles)
                    : deny(permission);

            // Consultant technical review is distinct from the client's final approval.
            case DOCUMENT_REVIEW_TECHNICAL -> reviewer && consultant
                    ? new Decision(actor, DataScope.ASSIGNED, orgId, roles)
                    : deny(permission);

            case DOCUMENT_APPROVE_CLIENT -> reviewer && client
                    ? new Decision(actor, DataScope.ASSIGNED, orgId, roles)
                    : deny(permission);

            // Commercial certification may be delegated to consultant or retained by client,
            // but still requires manager-level commercial authority.
            case DOCUMENT_CERTIFY_COMMERCIAL -> manager && (client || consultant)
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
