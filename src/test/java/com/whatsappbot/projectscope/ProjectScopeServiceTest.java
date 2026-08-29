package com.whatsappbot.projectscope;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.project.ProjectAccessService;
import com.whatsappbot.project.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectScopeServiceTest {

    @Mock ProjectScopeRepository repository;
    @Mock ProjectRepository projects;
    @Mock ProjectAccessService access;
    @Mock TenantUserEntity user;

    private ProjectScopeService service;
    private UUID tenantId;
    private UUID userId;
    private UUID projectId;
    private UUID typeId;

    @BeforeEach
    void setUp() {
        service = new ProjectScopeService(repository, projects, access, new ObjectMapper());
        tenantId = UUID.randomUUID();
        userId = UUID.randomUUID();
        projectId = UUID.randomUUID();
        typeId = UUID.randomUUID();
        when(access.requireActiveUser(tenantId, userId)).thenReturn(user);
        when(projects.existsByIdAndTenantId(projectId, tenantId)).thenReturn(true);
    }

    @Test
    void createsRootScopeWithoutForcingWorkItemOrLegacyHierarchy() {
        when(repository.findType(tenantId, typeId)).thenReturn(Optional.of(
                new ProjectScopeRepository.ScopeTypeRow(typeId, "STAGE", "Stage", "STAGE", 1, "{}", "ACTIVE")));

        service.create(tenantId, userId, projectId,
                new ProjectScopeService.CreateScopeRequest(null, typeId, "tender", "Tender", null,
                        null, null, null, null, null, null, 10, Map.of()));

        verify(access).requireProjectVisibility(tenantId, projectId, user);
        verify(access).requireProjectAdministrator(user);
        verify(repository).insert(any(UUID.class), eq(tenantId), eq(projectId), isNull(), eq(typeId),
                eq("TENDER"), eq("Tender"), isNull(), isNull(), eq("ACTIVE"),
                isNull(), isNull(), isNull(), isNull(), eq(10), eq("{}"));
    }

    @Test
    void rejectsParentFromAnotherProject() {
        UUID parent = UUID.randomUUID();
        when(repository.findType(tenantId, typeId)).thenReturn(Optional.of(
                new ProjectScopeRepository.ScopeTypeRow(typeId, "STAGE", "Stage", "STAGE", 1, "{}", "ACTIVE")));
        when(repository.findScope(tenantId, projectId, parent)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(tenantId, userId, projectId,
                new ProjectScopeService.CreateScopeRequest(parent, typeId, "DESIGN", "Design", null,
                        null, null, null, null, null, null, 0, Map.of())))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Project scope not found");

        verify(repository, never()).insert(any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    void rejectsCycleWhenReparentingScope() {
        UUID scopeId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();
        ProjectScopeRepository.ScopeRow current = new ProjectScopeRepository.ScopeRow(
                scopeId, tenantId, projectId, null, typeId, "STAGE", "Stage", "STAGE",
                "DESIGN", "Design", null, null, "ACTIVE", null, null, null, null, 0, "{}", 3);
        when(repository.findScope(tenantId, projectId, scopeId)).thenReturn(Optional.of(current));
        when(repository.findScope(tenantId, projectId, childId)).thenReturn(Optional.of(current));
        when(repository.findType(tenantId, typeId)).thenReturn(Optional.of(
                new ProjectScopeRepository.ScopeTypeRow(typeId, "STAGE", "Stage", "STAGE", 1, "{}", "ACTIVE")));
        when(repository.wouldCreateCycle(tenantId, projectId, scopeId, childId)).thenReturn(true);

        assertThatThrownBy(() -> service.update(tenantId, userId, projectId, scopeId,
                new ProjectScopeService.UpdateScopeRequest(childId, false, null, null, null, null,
                        null, false, null, null, null, null, null, null, null, 3L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("descendants");
    }

    @Test
    void rejectsStaleOptimisticVersion() {
        UUID scopeId = UUID.randomUUID();
        ProjectScopeRepository.ScopeRow current = new ProjectScopeRepository.ScopeRow(
                scopeId, tenantId, projectId, null, typeId, "STAGE", "Stage", "STAGE",
                "DESIGN", "Design", null, null, "ACTIVE", null, null, null, null, 0, "{}", 4);
        when(repository.findScope(tenantId, projectId, scopeId)).thenReturn(Optional.of(current));
        when(repository.findType(tenantId, typeId)).thenReturn(Optional.of(
                new ProjectScopeRepository.ScopeTypeRow(typeId, "STAGE", "Stage", "STAGE", 1, "{}", "ACTIVE")));
        when(repository.update(eq(tenantId), eq(projectId), eq(scopeId), eq(3L), any(), eq(typeId),
                anyString(), anyString(), any(), any(), anyString(), any(), any(), any(), any(), anyInt(), anyString()))
                .thenReturn(false);

        assertThatThrownBy(() -> service.update(tenantId, userId, projectId, scopeId,
                new ProjectScopeService.UpdateScopeRequest(null, false, null, null, "Design Updated", null,
                        null, false, null, null, null, null, null, null, null, 3L)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("reload and retry");
    }
}
