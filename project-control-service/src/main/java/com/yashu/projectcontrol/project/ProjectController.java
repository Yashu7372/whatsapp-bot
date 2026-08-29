package com.yashu.projectcontrol.project;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectService.ProjectView create(@Valid @RequestBody CreateProjectRequest request) {
        return service.create(
                request.workspaceId(),
                request.code(),
                request.name(),
                request.description(),
                request.startDate(),
                request.endDate(),
                request.currency(),
                request.timeZone());
    }

    @GetMapping("/{projectId}")
    public ProjectService.ProjectView get(@PathVariable UUID projectId) {
        return service.get(projectId);
    }

    public record CreateProjectRequest(
            @NotNull UUID workspaceId,
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 240) String name,
            @Size(max = 1000) String description,
            LocalDate startDate,
            LocalDate endDate,
            @NotBlank @Size(min = 3, max = 3) String currency,
            @NotBlank @Size(max = 64) String timeZone) {
    }
}
