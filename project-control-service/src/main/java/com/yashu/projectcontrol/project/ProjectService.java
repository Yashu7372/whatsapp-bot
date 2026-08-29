package com.yashu.projectcontrol.project;

import com.yashu.projectcontrol.workspace.WorkspaceService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.UUID;

@Service
public class ProjectService {

    private final ProjectRepository repository;
    private final WorkspaceService workspaceService;

    public ProjectService(ProjectRepository repository, WorkspaceService workspaceService) {
        this.repository = repository;
        this.workspaceService = workspaceService;
    }

    @Transactional
    public ProjectView create(
            UUID workspaceId,
            String code,
            String name,
            String description,
            LocalDate startDate,
            LocalDate endDate,
            String currency,
            String timeZone) {
        workspaceService.requireExists(workspaceId);

        String normalizedCode = normalizeCode(code);
        if (repository.existsByWorkspaceIdAndCodeIgnoreCase(workspaceId, normalizedCode)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Project code already exists in workspace: " + normalizedCode);
        }
        if (startDate != null && endDate != null && endDate.isBefore(startDate)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Project end date cannot be before start date");
        }

        String normalizedCurrency = currency.trim().toUpperCase(Locale.ROOT);
        if (normalizedCurrency.length() != 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Currency must be a three-letter code");
        }
        String normalizedTimeZone = timeZone.trim();
        try {
            ZoneId.of(normalizedTimeZone);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid time zone: " + normalizedTimeZone);
        }

        Project project = repository.save(Project.create(
                workspaceId,
                normalizedCode,
                name.trim(),
                normalizeOptional(description),
                startDate,
                endDate,
                normalizedCurrency,
                normalizedTimeZone));
        return toView(project);
    }

    @Transactional(readOnly = true)
    public ProjectView get(UUID id) {
        return toView(requireEntity(id));
    }

    @Transactional(readOnly = true)
    public void requireExists(UUID id) {
        requireEntity(id);
    }

    private Project requireEntity(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found: " + id));
    }

    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ProjectView toView(Project project) {
        return new ProjectView(
                project.getId(),
                project.getWorkspaceId(),
                project.getCode(),
                project.getName(),
                project.getDescription(),
                project.getStatus().name(),
                project.getStartDate(),
                project.getEndDate(),
                project.getCurrency(),
                project.getTimeZone());
    }

    public record ProjectView(
            UUID id,
            UUID workspaceId,
            String code,
            String name,
            String description,
            String status,
            LocalDate startDate,
            LocalDate endDate,
            String currency,
            String timeZone) {
    }
}
