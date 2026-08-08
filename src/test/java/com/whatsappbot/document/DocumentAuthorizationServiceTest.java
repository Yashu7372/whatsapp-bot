package com.whatsappbot.document;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.UserRole;
import com.whatsappbot.project.ProjectAccessService;
import com.whatsappbot.project.ProjectAuthorizationService;
import com.whatsappbot.project.ProjectPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Covers the rules that decide who may read, change and declassify a controlled document.
 * These paths carried no tests at all despite being the security boundary of the feature.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DocumentAuthorizationServiceTest {

    @Mock DocumentAuthorizationRepository repository;
    @Mock ProjectAccessService accessService;
    @Mock ProjectAuthorizationService projectAuthorization;

    DocumentAuthorizationService service;

    final UUID tenant = UUID.randomUUID();
    final UUID document = UUID.randomUUID();
    final UUID project = UUID.randomUUID();
    final UUID ownerOrg = UUID.randomUUID();
    final UUID otherOrg = UUID.randomUUID();
    final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new DocumentAuthorizationService(repository, accessService, projectAuthorization);
    }

    @Test
    @DisplayName("an EDIT grant satisfies a VIEW check")
    void editGrantImpliesView() {
        TenantUserEntity actor = actor(UserRole.REVIEWER, otherOrg);
        security(project, ownerOrg, DocumentClassification.RESTRICTED);
        // The holder has EDIT only; VIEW is requested. The implication set is what makes this pass.
        when(repository.hasGrant(eq(tenant), eq(document), eq(userId), eq(otherOrg), anyString(),
                argThatContains(DocumentAuthorizationService.EDIT))).thenReturn(true);

        assertThat(actor).isNotNull();
        service.requireView(tenant, userId, document);
    }

    @Test
    @DisplayName("a restricted document is not readable without a grant or an assignment")
    void restrictedRequiresGrantOrAssignment() {
        actor(UserRole.REVIEWER, otherOrg);
        security(project, ownerOrg, DocumentClassification.RESTRICTED);
        when(repository.hasGrant(any(), any(), any(), any(), anyString(), any())).thenReturn(false);
        when(repository.assignedToApproval(any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.requireView(tenant, userId, document))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    @DisplayName("an EDIT grant does not confer authority to declassify")
    void editGrantCannotAdministerSecurity() {
        actor(UserRole.MANAGER, otherOrg);
        security(project, ownerOrg, DocumentClassification.RESTRICTED);
        when(projectAuthorization.require(tenant, userId, project, ProjectPermission.DOCUMENT_SECURITY_ADMIN))
                .thenReturn(null);
        // Holds EDIT, but not MANAGE, and is not the originating company.
        when(repository.hasGrant(eq(tenant), eq(document), eq(userId), eq(otherOrg), anyString(),
                eq(List.of(DocumentAuthorizationService.MANAGE)))).thenReturn(false);

        assertThatThrownBy(() -> service.requireSecurityAdministration(tenant, userId, document))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("administer document security");
    }

    @Test
    @DisplayName("the originating company may administer its own document security")
    void originatorMayAdministerSecurity() {
        actor(UserRole.MANAGER, ownerOrg);
        security(project, ownerOrg, DocumentClassification.RESTRICTED);
        when(projectAuthorization.require(tenant, userId, project, ProjectPermission.DOCUMENT_SECURITY_ADMIN))
                .thenReturn(null);

        service.requireSecurityAdministration(tenant, userId, document);
    }

    @Test
    @DisplayName("a tenant administrator keeps break-glass access")
    void tenantAdministratorMayAdministerSecurity() {
        TenantUserEntity admin = actor(UserRole.ADMIN, null);
        when(accessService.isTenantAdministrator(admin)).thenReturn(true);
        security(project, ownerOrg, DocumentClassification.RESTRICTED);

        service.requireSecurityAdministration(tenant, userId, document);
    }

    @Test
    @DisplayName("a missing document is a 404, not a silent denial")
    void missingDocumentIsNotFound() {
        when(repository.security(tenant, document)).thenReturn(null);

        assertThatThrownBy(() -> service.requireSecurityAdministration(tenant, userId, document))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    private TenantUserEntity actor(UserRole role, UUID organizationId) {
        TenantUserEntity user = new TenantUserEntity();
        user.setId(userId);
        user.setRole(role);
        user.setOrganizationId(organizationId);
        user.setActive(true);
        user.setEmail("actor@example.test");
        lenient().when(accessService.requireActiveUser(tenant, userId)).thenReturn(user);
        lenient().when(accessService.isTenantAdministrator(user)).thenReturn(false);
        return user;
    }

    private void security(UUID projectId, UUID originatorOrg, DocumentClassification classification) {
        lenient().when(repository.security(tenant, document)).thenReturn(
                new DocumentAuthorizationRepository.DocumentSecurity(projectId, originatorOrg, classification.name()));
    }

    private static List<String> argThatContains(String value) {
        return org.mockito.ArgumentMatchers.argThat(list -> list != null && list.contains(value));
    }
}
