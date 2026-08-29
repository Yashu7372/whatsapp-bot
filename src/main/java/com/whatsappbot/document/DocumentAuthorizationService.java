package com.whatsappbot.document;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.UserRole;
import com.whatsappbot.project.PartyRole;
import com.whatsappbot.project.ProjectAccessService;
import com.whatsappbot.project.ProjectAuthorizationService;
import com.whatsappbot.project.ProjectPermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Applies project scope, creator ownership, explicit grants and active workflow assignment. */
@Service
@RequiredArgsConstructor
public class DocumentAuthorizationService {
    public static final String VIEW = "VIEW";
    public static final String EDIT = "EDIT";
    public static final String ISSUE = "ISSUE";
    /** Administers classification and grants. Strictly stronger than EDIT. */
    public static final String MANAGE = "MANAGE";

    /**
     * Which stored grant codes satisfy a requested permission. A grant of EDIT necessarily implies
     * the holder may read what they are editing; without this the holder of an EDIT-only grant
     * could PATCH a restricted document but be refused when fetching it.
     */
    private static final Map<String, List<String>> IMPLYING_GRANTS = Map.of(
            VIEW, List.of(VIEW, EDIT, ISSUE, MANAGE),
            EDIT, List.of(EDIT, MANAGE),
            ISSUE, List.of(ISSUE, MANAGE),
            MANAGE, List.of(MANAGE));

    private final DocumentAuthorizationRepository repository;
    private final ProjectAccessService accessService;
    private final ProjectAuthorizationService projectAuthorization;

    @Transactional(readOnly = true)
    public void requireView(UUID tenantId, UUID userId, UUID documentId) {
        evaluate(tenantId, userId, documentId, VIEW, false);
    }

    @Transactional(readOnly = true)
    public void requireEdit(UUID tenantId, UUID userId, UUID documentId) {
        evaluate(tenantId, userId, documentId, EDIT, true);
    }

    @Transactional(readOnly = true)
    public void requireIssue(UUID tenantId, UUID userId, UUID documentId) {
        evaluate(tenantId, userId, documentId, ISSUE, true);
    }

    /**
     * Document security is a business authority, not a system-administration privilege. ADMIN is
     * intentionally excluded. A manager may administer tenant-level documents; project documents
     * still require project authority plus originator ownership or an explicit MANAGE grant.
     */
    @Transactional(readOnly = true)
    public void requireSecurityAdministration(UUID tenantId, UUID userId, UUID documentId) {
        DocumentAuthorizationRepository.DocumentSecurity security = repository.security(tenantId, documentId);
        if (security == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: " + documentId);
        }

        TenantUserEntity actor = accessService.requireActiveUser(tenantId, userId);
        denySystemAdmin(actor, documentId);

        if (security.projectId() == null) {
            if (isManager(actor) || hasGrant(tenantId, documentId, actor, MANAGE)) return;
            throw securityDenied(documentId);
        }

        projectAuthorization.require(tenantId, userId, security.projectId(), ProjectPermission.DOCUMENT_SECURITY_ADMIN);
        boolean originator = actor.getOrganizationId() != null
                && actor.getOrganizationId().equals(security.originatorOrganizationId());
        if (originator || hasGrant(tenantId, documentId, actor, MANAGE)) return;
        throw securityDenied(documentId);
    }

    /** Enforces the current workflow step's contractual authority before DocumentService mutates it. */
    @Transactional(readOnly = true)
    public void requireApprovalDecision(UUID tenantId, UUID userId, UUID approvalId) {
        DocumentAuthorizationRepository.ApprovalAuthority ctx = repository.approvalAuthority(tenantId, approvalId);
        if (ctx == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Pending approval not found: " + approvalId);
        }

        TenantUserEntity actor = accessService.requireActiveUser(tenantId, userId);
        if (isAdmin(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "System administrators do not make business-document approval decisions");
        }

        if (ctx.projectId() == null) {
            if (ctx.reviewerEmail() != null && !ctx.reviewerEmail().equalsIgnoreCase(actor.getEmail())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "This workflow step is assigned to another reviewer");
            }
            return;
        }

        ProjectPermission permission = ApprovalAuthority.of(ctx.authorityType()).permission();
        ProjectAuthorizationService.Decision decision = projectAuthorization.require(
                tenantId, userId, ctx.projectId(), permission);

        if ("USER".equals(ctx.assignmentType()) && ctx.reviewerEmail() != null
                && !ctx.reviewerEmail().equalsIgnoreCase(decision.actor().getEmail())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This workflow step is assigned to another reviewer");
        }
        if ("ORGANIZATION".equals(ctx.assignmentType()) && ctx.assignmentOrganizationId() != null
                && !ctx.assignmentOrganizationId().equals(decision.organizationId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "This workflow step is assigned to another organization");
        }
        if ("PARTY_ROLE".equals(ctx.assignmentType()) && ctx.assignmentPartyRole() != null) {
            PartyRole required;
            try {
                required = PartyRole.valueOf(ctx.assignmentPartyRole());
            } catch (IllegalArgumentException ex) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Workflow has invalid party role assignment");
            }
            if (!decision.partyRoles().contains(required)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "This workflow step is assigned to " + required);
            }
        }
        if ("INTERNAL_REVIEW".equals(ctx.authorityType()) && ctx.originatorOrganizationId() != null
                && !ctx.originatorOrganizationId().equals(decision.organizationId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Internal review belongs to the originating organization");
        }
    }

    private void evaluate(UUID tenantId, UUID userId, UUID documentId,
                          String grantPermission, boolean mutation) {
        DocumentAuthorizationRepository.DocumentSecurity security = repository.security(tenantId, documentId);
        if (security == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Document not found: " + documentId);
        }

        TenantUserEntity actor = accessService.requireActiveUser(tenantId, userId);
        denySystemAdmin(actor, documentId);

        boolean manager = isManager(actor);
        boolean creator = actor.getId().equals(security.createdByUserId());
        boolean explicit = hasGrant(tenantId, documentId, actor, grantPermission);
        boolean assigned = !mutation && repository.assignedToApproval(
                tenantId, documentId, actor.getEmail(), actor.getOrganizationId());

        if (security.projectId() == null) {
            if (!mutation) {
                if (manager || creator || explicit || assigned) return;
                throw denied(documentId);
            }

            String tenantClassification = security.classification() == null
                    ? DocumentClassification.PROJECT.name() : security.classification();
            boolean permitted = switch (DocumentClassification.of(tenantClassification, documentId)) {
                case PROJECT -> manager || explicit;
                case ORGANIZATION, RESTRICTED -> explicit;
            };
            if (permitted) return;
            throw denied(documentId);
        }

        ProjectPermission projectPermission = mutation
                ? (ISSUE.equals(grantPermission)
                    ? ProjectPermission.DOCUMENT_ISSUE
                    : ProjectPermission.DOCUMENT_EDIT)
                : ProjectPermission.DOCUMENT_VIEW;
        projectAuthorization.require(tenantId, userId, security.projectId(), projectPermission);

        if (!mutation) {
            if (manager || creator || explicit || assigned) return;
            throw denied(documentId);
        }

        boolean originator = actor.getOrganizationId() != null
                && actor.getOrganizationId().equals(security.originatorOrganizationId());
        String classification = security.classification() == null
                ? DocumentClassification.PROJECT.name() : security.classification();
        boolean allowed = switch (DocumentClassification.of(classification, documentId)) {
            case PROJECT, ORGANIZATION -> originator || explicit;
            case RESTRICTED -> explicit;
        };
        if (!allowed) throw denied(documentId);
    }

    private boolean hasGrant(UUID tenantId, UUID documentId, TenantUserEntity actor, String permission) {
        return repository.hasGrant(tenantId, documentId, actor.getId(), actor.getOrganizationId(),
                actor.getRole().name(), IMPLYING_GRANTS.getOrDefault(permission, List.of(permission)));
    }

    private static boolean isAdmin(TenantUserEntity actor) {
        return actor.getRole() == UserRole.ADMIN;
    }

    private static boolean isManager(TenantUserEntity actor) {
        return actor.getRole() == UserRole.MANAGER;
    }

    private static void denySystemAdmin(TenantUserEntity actor, UUID documentId) {
        if (isAdmin(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "System administrators do not have business-document content access: " + documentId);
        }
    }

    private static ResponseStatusException denied(UUID documentId) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Document access is limited to managers, the creator, an active reviewer or an explicit grant: "
                        + documentId);
    }

    private static ResponseStatusException securityDenied(UUID documentId) {
        return new ResponseStatusException(HttpStatus.FORBIDDEN,
                "Only an authorized manager/originator or MANAGE grant holder can administer document security: "
                        + documentId);
    }
}
