package com.yashu.projectcontrol.workspace;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces")
public class WorkspaceController {

    private final WorkspaceService service;

    public WorkspaceController(WorkspaceService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkspaceService.WorkspaceView create(@Valid @RequestBody CreateWorkspaceRequest request) {
        return service.create(request.code(), request.name());
    }

    @GetMapping("/{workspaceId}")
    public WorkspaceService.WorkspaceView get(@PathVariable UUID workspaceId) {
        return service.get(workspaceId);
    }

    public record CreateWorkspaceRequest(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 200) String name) {
    }
}
