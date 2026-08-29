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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** Covers the business-document visibility and security boundary. */
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
        actor(UserRole.REVIEWER, otherOrg);
        security(project, ownerOrg, DocumentClassification.RESTRICTED, UUID.randomUUID());
        when(repository.hasGrant(eq(tenant), eq(document), eq(userId), eq(otherOrg), anyString(),
                argThatContains(DocumentAuthorizationService.EDIT))).thenReturn(true);

        service.requireView(tenant, userId, document);
    }

    @Test
    @DisplayName("a reviewer cannot read a document without creator, active assignment or grant access")
    void reviewerRequiresBusinessRelationshipToDocument() {
        actor(UserRole.REVIEWER, otherOrg);
        security(project, ownerOrg, DocumentClassification.PROJECT, UUID.randomUUID());
        when(repository.hasGrant(any(), any(), any(), any(), anyString(), any())).thenReturn(false);
        when(repository.assignedToApproval(any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.requireView(tenant, userId, document))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    @DisplayName("the document creator may read their own project document")
    void creatorMayViewOwnDocument() {
        actor(UserRole.REVIEWER, otherOrg);
        security(project, ownerOrg, DocumentClassification.RESTRICTED, userId);

        service.requireView(tenant, userId, document);
    }

    @Test
    @DisplayName("a manager may read documents inside their project scope")
    void managerMayViewProjectDocument() {
        actor(UserRole.MANAGER, otherOrg);
        security(project, ownerOrg, DocumentClassification.RESTRICTED, UUID.randomUUID());

        service.requireView(tenant, userId, document);
    }

    @Test
    @DisplayName("system ADMIN does not receive business-document content access")
    void adminCannotViewBusinessDocument() {
        actor(UserRole.ADMIN, null);
        security(project, ownerOrg, DocumentClassification.PROJECT, UUID.randomUUID());

        assertThatThrownBy(() -> service.requireView(tenant, userId, document))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("System administrators");
    }

    @Test
    @DisplayName("an active workflow assignment lets the reviewer open what they must approve")
    void activeAssignmentConfersView() {
        actor(UserRole.REVIEWER, otherOrg);
        security(project, ownerOrg, DocumentClassification.RESTRICTED, UUID.randomUUID());
        when(repository.hasGrant(any(), any(), any(), any(), anyString(), any())).thenReturn(false);
        when(repository.assignedToApproval(tenant, document, "actor@example.test", otherOrg)).thenReturn(true);

        service.requireView(tenant, userId, document);
    }

    @Test
    @DisplayName("tenant-level documents are not automatically readable by a VIEWER")
    void tenantLevelDocumentIsNotOpenToEveryone() {
        actor(UserRole.VIEWER, null);
        security(null, null, DocumentClassification.PROJECT, UUID.randomUUID());
        when(repository.hasGrant(any(), any(), any(), any(), anyString(), any())).thenReturn(false);
        when(repository.assignedToApproval(any(), any(), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.requireView(tenant, userId, document))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403");
    }

    @Test
    @DisplayName("a manager may read a tenant-level business document")
    void managerMayViewTenantLevelDocument() {
        actor(UserRole.MANAGER, otherOrg);
        security(null, null, DocumentClassification.RESTRICTED, UUID.randomUUID());

        service.requireView(tenant, userId, document);
    }

    @Test
    @DisplayName("an EDIT grant does not confer authority to declassify")
    void editGrantCannotAdministerSecurity() {
        actor(UserRole.MANAGER, otherOrg);
        security(project, ownerOrg, DocumentClassification.RESTRICTED, UUID.randomUUID());
        when(projectAuthorization.require(tenant, userId, project, ProjectPermission.DOCUMENT_SECURITY_ADMIN))
                .thenReturn(null);
        when(repository.hasGrant(eq(tenant), eq(document), eq(userId), eq(otherOrg), anyString(),
                eq(List.of(DocumentAuthorizationService.MANAGE)))).thenReturn(false);

        assertThatThrownBy(() -> service.requireSecurityAdministration(tenant, userId, document))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("administer document security");
    }

    @Test
    @DisplayName("the originating company manager may administer its own document security")
    void originatorManagerMayAdministerSecurity() {
        actor(UserRole.MANAGER, ownerOrg);
        security(project, ownerOrg, DocumentClassification.RESTRICTED, UUID.randomUUID());
        when(projectAuthorization.require(tenant, userId, project, ProjectPermission.DOCUMENT_SECURITY_ADMIN))
                .thenReturn(null);

        service.requireSecurityAdministration(tenant, userId, document);
    }

    @Test
    @DisplayName("system ADMIN cannot administer business-document security")
    void adminCannotAdministerDocumentSecurity() {
        actor(UserRole.ADMIN, null);
        security(project, ownerOrg, DocumentClassification.RESTRICTED, UUID.randomUUID());

        assertThatThrownBy(() -> service.requireSecurityAdministration(tenant, userId, document))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("403")
                .hasMessageContaining("System administrators");
    }

    @Test
    @DisplayName("a manager may administer security on a tenant-level document")
    void managerAdministersTenantLevelSecurity() {
        actor(UserRole.MANAGER, otherOrg);
        security(null, null, DocumentClassification.RESTRICTED, UUID.randomUUID());

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
        return user;
    }

    private void security(UUID projectId, UUID originatorOrg, DocumentClassification classification,
                          UUID createdByUserId) {
        lenient().when(repository.security(tenant, document)).thenReturn(
                new DocumentAuthorizationRepository.DocumentSecurity(
                        projectId, originatorOrg, classification.name(), createdByUserId));
    }

    private static List<String> argThatContains(String value) {
        return org.mockito.ArgumentMatchers.argThat(list -> list != null && list.contains(value));
    }
}
