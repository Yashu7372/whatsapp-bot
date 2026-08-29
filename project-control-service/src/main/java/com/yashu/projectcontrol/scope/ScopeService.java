package com.yashu.projectcontrol.scope;

import com.yashu.projectcontrol.participation.ParticipationService;
import com.yashu.projectcontrol.project.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class ScopeService {

    private final ProjectScopeRepository scopeRepository;
    private final ScopeParticipantRepository scopeParticipantRepository;
    private final ScopeCapabilityRepository capabilityRepository;
    private final ProjectService projectService;
    private final ParticipationService participationService;

    public ScopeService(
            ProjectScopeRepository scopeRepository,
            ScopeParticipantRepository scopeParticipantRepository,
            ScopeCapabilityRepository capabilityRepository,
            ProjectService projectService,
            ParticipationService participationService) {
        this.scopeRepository = scopeRepository;
        this.scopeParticipantRepository = scopeParticipantRepository;
        this.capabilityRepository = capabilityRepository;
        this.projectService = projectService;
        this.participationService = participationService;
    }

    @Transactional
    public ScopeView create(
            UUID projectId,
            UUID parentScopeId,
            String scopeType,
            String code,
            String name,
            String description,
            LocalDate startDate,
            LocalDate endDate,
            String configurationJson) {
        projectService.requireExists(projectId);

        if (parentScopeId != null) {
            requireScope(projectId, parentScopeId);
        }
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Scope end date cannot be before start date");
        }

        String normalizedCode = normalizeCode(code);
        if (scopeRepository.existsByProjectIdAndCodeIgnoreCase(projectId, normalizedCode)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Scope code already exists in project: " + normalizedCode);
        }

        ProjectScope scope = scopeRepository.save(ProjectScope.create(
                projectId,
                parentScopeId,
                normalizeCode(scopeType),
                normalizedCode,
                name.trim(),
                normalizeOptional(description),
                startDate,
                endDate,
                normalizeJson(configurationJson)));
        return toView(scope);
    }

    @Transactional(readOnly = true)
    public List<ScopeView> listByProject(UUID projectId) {
        projectService.requireExists(projectId);
        return scopeRepository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .map(ScopeService::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public void requireExistsInProject(UUID projectId, UUID scopeId) {
        requireScope(projectId, scopeId);
    }

    @Transactional(readOnly = true)
    public void requireEnabledCapability(UUID projectId, UUID scopeId, String capabilityCode) {
        requireScope(projectId, scopeId);
        String normalizedCapability = normalizeCode(capabilityCode);
        ScopeCapability capability = capabilityRepository.findByScopeIdAndCapabilityCode(scopeId, normalizedCapability)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Scope does not have required capability: " + normalizedCapability));
        if (!capability.isEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Scope capability is disabled: " + normalizedCapability);
        }
    }

    @Transactional
    public ScopeAssignmentView assignParticipant(
            UUID projectId,
            UUID scopeId,
            UUID projectParticipantId,
            String responsibility) {
        ProjectScope scope = requireScope(projectId, scopeId);
        participationService.requireBelongsToProject(projectId, projectParticipantId);

        if (scopeParticipantRepository.existsByScopeIdAndProjectParticipantId(scopeId, projectParticipantId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Participant is already assigned to scope");
        }

        ScopeParticipant assignment = scopeParticipantRepository.save(ScopeParticipant.create(
                projectId,
                scopeId,
                projectParticipantId,
                normalizeOptional(responsibility)));
        return toAssignmentView(assignment, scope);
    }

    @Transactional
    public CapabilityView setCapability(
            UUID projectId,
            UUID scopeId,
            String capabilityCode,
            boolean enabled,
            String configurationJson) {
        requireScope(projectId, scopeId);
        String normalizedCapability = normalizeCode(capabilityCode);
        String normalizedConfiguration = normalizeJson(configurationJson);

        ScopeCapability capability = capabilityRepository
                .findByScopeIdAndCapabilityCode(scopeId, normalizedCapability)
                .map(existing -> {
                    existing.configure(enabled, normalizedConfiguration);
                    return existing;
                })
                .orElseGet(() -> ScopeCapability.create(
                        projectId,
                        scopeId,
                        normalizedCapability,
                        enabled,
                        normalizedConfiguration));

        return toCapabilityView(capabilityRepository.save(capability));
    }

    @Transactional(readOnly = true)
    public List<CapabilityView> listCapabilities(UUID projectId, UUID scopeId) {
        requireScope(projectId, scopeId);
        return capabilityRepository.findByScopeIdOrderByCapabilityCodeAsc(scopeId).stream()
                .map(ScopeService::toCapabilityView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ScopeAssignmentView> listAssignmentsForParticipant(UUID projectParticipantId) {
        return scopeParticipantRepository.findByProjectParticipantIdOrderByCreatedAtAsc(projectParticipantId).stream()
                .map(assignment -> {
                    ProjectScope scope = scopeRepository.findById(assignment.getScopeId())
                            .orElseThrow(() -> new ResponseStatusException(
                                    HttpStatus.INTERNAL_SERVER_ERROR,
                                    "Scope assignment references missing scope: " + assignment.getScopeId()));
                    return toAssignmentView(assignment, scope);
                })
                .toList();
    }

    private ProjectScope requireScope(UUID projectId, UUID scopeId) {
        return scopeRepository.findByIdAndProjectId(scopeId, projectId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Scope not found in project: " + scopeId));
    }

    private ScopeAssignmentView toAssignmentView(ScopeParticipant assignment, ProjectScope scope) {
        List<CapabilityView> capabilities = capabilityRepository.findByScopeIdOrderByCapabilityCodeAsc(scope.getId()).stream()
                .map(ScopeService::toCapabilityView)
                .toList();
        return new ScopeAssignmentView(
                assignment.getId(),
                assignment.getProjectId(),
                assignment.getScopeId(),
                assignment.getProjectParticipantId(),
                assignment.getResponsibility(),
                scope.getCode(),
                scope.getName(),
                scope.getScopeType(),
                capabilities);
    }

    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String normalizeJson(String value) {
        return value == null || value.isBlank() ? "{}" : value.trim();
    }

    private static ScopeView toView(ProjectScope scope) {
        return new ScopeView(
                scope.getId(),
                scope.getProjectId(),
                scope.getParentScopeId(),
                scope.getScopeType(),
                scope.getCode(),
                scope.getName(),
                scope.getDescription(),
                scope.getStatus().name(),
                scope.getStartDate(),
                scope.getEndDate(),
                scope.getConfigurationJson());
    }

    private static CapabilityView toCapabilityView(ScopeCapability capability) {
        return new CapabilityView(
                capability.getId(),
                capability.getProjectId(),
                capability.getScopeId(),
                capability.getCapabilityCode(),
                capability.isEnabled(),
                capability.getConfigurationJson());
    }

    public record ScopeView(
            UUID id,
            UUID projectId,
            UUID parentScopeId,
            String scopeType,
            String code,
            String name,
            String description,
            String status,
            LocalDate startDate,
            LocalDate endDate,
            String configurationJson) {
    }

    public record CapabilityView(
            UUID id,
            UUID projectId,
            UUID scopeId,
            String capabilityCode,
            boolean enabled,
            String configurationJson) {
    }

    public record ScopeAssignmentView(
            UUID assignmentId,
            UUID projectId,
            UUID scopeId,
            UUID projectParticipantId,
            String responsibility,
            String scopeCode,
            String scopeName,
            String scopeType,
            List<CapabilityView> capabilities) {
    }
}
