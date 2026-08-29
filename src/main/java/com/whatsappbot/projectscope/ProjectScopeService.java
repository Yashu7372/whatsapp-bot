package com.whatsappbot.projectscope;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.project.ProjectAccessService;
import com.whatsappbot.project.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
public class ProjectScopeService {

    private static final Pattern CODE = Pattern.compile("[A-Z0-9][A-Z0-9_.-]{0,99}");
    private static final Pattern CAPABILITY = Pattern.compile("[A-Z][A-Z0-9_]{1,99}");

    private final ProjectScopeRepository repository;
    private final ProjectRepository projectRepository;
    private final ProjectAccessService accessService;
    private final ObjectMapper objectMapper;

    @Transactional(readOnly = true)
    public List<ProjectScopeRepository.ScopeTypeRow> listTypes(UUID tenantId, UUID userId) {
        accessService.requireActiveUser(tenantId, userId);
        return repository.listTypes(tenantId);
    }

    @Transactional(readOnly = true)
    public List<ProjectScopeRepository.ScopeRow> list(UUID tenantId, UUID userId, UUID projectId) {
        TenantUserEntity user = accessService.requireActiveUser(tenantId, userId);
        requireProject(tenantId, projectId);
        accessService.requireProjectVisibility(tenantId, projectId, user);
        return repository.listScopes(tenantId, projectId);
    }

    @Transactional(readOnly = true)
    public ProjectScopeRepository.ScopeRow get(UUID tenantId, UUID userId, UUID projectId, UUID scopeId) {
        TenantUserEntity user = accessService.requireActiveUser(tenantId, userId);
        requireProject(tenantId, projectId);
        accessService.requireProjectVisibility(tenantId, projectId, user);
        return requireScope(tenantId, projectId, scopeId);
    }

    @Transactional
    public ProjectScopeRepository.ScopeRow create(UUID tenantId, UUID userId, UUID projectId,
                                                   CreateScopeRequest request) {
        TenantUserEntity user = accessService.requireActiveUser(tenantId, userId);
        requireProject(tenantId, projectId);
        accessService.requireProjectVisibility(tenantId, projectId, user);
        accessService.requireProjectAdministrator(user);

        validateText(request.code(), "code");
        validateText(request.name(), "name");
        String code = request.code().trim().toUpperCase();
        if (!CODE.matcher(code).matches()) {
            throw badRequest("Scope code may contain only A-Z, 0-9, '.', '_' and '-'");
        }
        requireType(tenantId, request.scopeTypeId());
        validateParent(tenantId, projectId, null, request.parentScopeId());
        validateOwner(tenantId, request.ownerOrganizationId());
        validateDates(request.plannedStart(), request.plannedFinish(), "planned");
        validateDates(request.actualStart(), request.actualFinish(), "actual");

        return repository.insert(UUID.randomUUID(), tenantId, projectId, request.parentScopeId(),
                request.scopeTypeId(), code, request.name().trim(), trimToNull(request.description()),
                request.ownerOrganizationId(), normalizedStatus(request.status()), request.plannedStart(),
                request.plannedFinish(), request.actualStart(), request.actualFinish(),
                request.sortOrder() == null ? 0 : request.sortOrder(), json(request.configuration()));
    }

    @Transactional
    public ProjectScopeRepository.ScopeRow update(UUID tenantId, UUID userId, UUID projectId, UUID scopeId,
                                                   UpdateScopeRequest request) {
        TenantUserEntity user = accessService.requireActiveUser(tenantId, userId);
        requireProject(tenantId, projectId);
        accessService.requireProjectVisibility(tenantId, projectId, user);
        accessService.requireProjectAdministrator(user);

        ProjectScopeRepository.ScopeRow current = requireScope(tenantId, projectId, scopeId);
        if (request.version() == null) {
            throw badRequest("version is required for scope updates");
        }
        UUID parent = Boolean.TRUE.equals(request.rootScope()) ? null
                : request.parentScopeId() != null ? request.parentScopeId() : current.parentScopeId();
        UUID typeId = request.scopeTypeId() != null ? request.scopeTypeId() : current.scopeTypeId();
        String code = request.code() != null ? request.code().trim().toUpperCase() : current.code();
        String name = request.name() != null ? request.name().trim() : current.name();
        String description = request.description() != null ? trimToNull(request.description()) : current.description();
        UUID owner = request.ownerOrganizationId() != null ? request.ownerOrganizationId() : current.ownerOrganizationId();
        if (Boolean.TRUE.equals(request.clearOwnerOrganization())) {
            owner = null;
        }
        String status = request.status() != null ? normalizedStatus(request.status()) : current.status();
        LocalDate plannedStart = request.plannedStart() != null ? request.plannedStart() : current.plannedStart();
        LocalDate plannedFinish = request.plannedFinish() != null ? request.plannedFinish() : current.plannedFinish();
        LocalDate actualStart = request.actualStart() != null ? request.actualStart() : current.actualStart();
        LocalDate actualFinish = request.actualFinish() != null ? request.actualFinish() : current.actualFinish();
        int sortOrder = request.sortOrder() != null ? request.sortOrder() : current.sortOrder();
        String configuration = request.configuration() != null ? json(request.configuration()) : current.configurationJson();

        validateText(code, "code");
        validateText(name, "name");
        if (!CODE.matcher(code).matches()) {
            throw badRequest("Scope code may contain only A-Z, 0-9, '.', '_' and '-'");
        }
        requireType(tenantId, typeId);
        validateParent(tenantId, projectId, scopeId, parent);
        validateOwner(tenantId, owner);
        validateDates(plannedStart, plannedFinish, "planned");
        validateDates(actualStart, actualFinish, "actual");

        boolean updated = repository.update(tenantId, projectId, scopeId, request.version(), parent, typeId,
                code, name, description, owner, status, plannedStart, plannedFinish, actualStart,
                actualFinish, sortOrder, configuration);
        if (!updated) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Project scope changed since it was read; reload and retry with the current version");
        }
        return requireScope(tenantId, projectId, scopeId);
    }

    @Transactional(readOnly = true)
    public List<ProjectScopeRepository.CapabilityRow> capabilities(UUID tenantId, UUID userId,
                                                                    UUID projectId, UUID scopeId) {
        TenantUserEntity user = accessService.requireActiveUser(tenantId, userId);
        requireProject(tenantId, projectId);
        accessService.requireProjectVisibility(tenantId, projectId, user);
        requireScope(tenantId, projectId, scopeId);
        return repository.listCapabilities(tenantId, projectId, scopeId);
    }

    @Transactional
    public ProjectScopeRepository.CapabilityRow putCapability(UUID tenantId, UUID userId, UUID projectId,
                                                               UUID scopeId, String capabilityCode,
                                                               PutCapabilityRequest request) {
        TenantUserEntity user = accessService.requireActiveUser(tenantId, userId);
        requireProject(tenantId, projectId);
        accessService.requireProjectVisibility(tenantId, projectId, user);
        accessService.requireProjectAdministrator(user);
        requireScope(tenantId, projectId, scopeId);

        String code = capabilityCode == null ? "" : capabilityCode.trim().toUpperCase();
        if (!CAPABILITY.matcher(code).matches()) {
            throw badRequest("Invalid capability code");
        }
        String mode = request.mode() == null ? "ENABLED" : request.mode().trim().toUpperCase();
        if (!List.of("ENABLED", "DISABLED", "INHERIT").contains(mode)) {
            throw badRequest("Capability mode must be ENABLED, DISABLED or INHERIT");
        }
        return repository.putCapability(tenantId, projectId, scopeId, code, mode, json(request.configuration()));
    }

    private void requireProject(UUID tenantId, UUID projectId) {
        if (!projectRepository.existsByIdAndTenantId(projectId, tenantId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found");
        }
    }

    private ProjectScopeRepository.ScopeRow requireScope(UUID tenantId, UUID projectId, UUID scopeId) {
        return repository.findScope(tenantId, projectId, scopeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project scope not found"));
    }

    private void requireType(UUID tenantId, UUID typeId) {
        if (typeId == null || repository.findType(tenantId, typeId).isEmpty()) {
            throw badRequest("scopeTypeId does not identify a scope type in this tenant");
        }
    }

    private void validateParent(UUID tenantId, UUID projectId, UUID scopeId, UUID parentScopeId) {
        if (parentScopeId == null) {
            return;
        }
        requireScope(tenantId, projectId, parentScopeId);
        if (scopeId != null && repository.wouldCreateCycle(tenantId, projectId, scopeId, parentScopeId)) {
            throw badRequest("A scope cannot be moved beneath itself or one of its descendants");
        }
    }

    private void validateOwner(UUID tenantId, UUID ownerOrganizationId) {
        if (ownerOrganizationId != null && !repository.organizationExists(tenantId, ownerOrganizationId)) {
            throw badRequest("ownerOrganizationId does not identify an active organization in this tenant");
        }
    }

    private static void validateDates(LocalDate start, LocalDate finish, String label) {
        if (start != null && finish != null && finish.isBefore(start)) {
            throw badRequest(label + " finish cannot be before " + label + " start");
        }
    }

    private String json(Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (JsonProcessingException ex) {
            throw badRequest("configuration must be valid JSON");
        }
    }

    private static String normalizedStatus(String status) {
        return status == null || status.isBlank() ? "ACTIVE" : status.trim().toUpperCase();
    }

    private static void validateText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw badRequest(field + " is required");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record CreateScopeRequest(UUID parentScopeId, UUID scopeTypeId, String code, String name,
                                     String description, UUID ownerOrganizationId, String status,
                                     LocalDate plannedStart, LocalDate plannedFinish,
                                     LocalDate actualStart, LocalDate actualFinish,
                                     Integer sortOrder, Map<String, Object> configuration) {}

    public record UpdateScopeRequest(UUID parentScopeId, Boolean rootScope, UUID scopeTypeId, String code,
                                     String name, String description, UUID ownerOrganizationId,
                                     Boolean clearOwnerOrganization, String status,
                                     LocalDate plannedStart, LocalDate plannedFinish,
                                     LocalDate actualStart, LocalDate actualFinish,
                                     Integer sortOrder, Map<String, Object> configuration, Long version) {}

    public record PutCapabilityRequest(String mode, Map<String, Object> configuration) {}
}
