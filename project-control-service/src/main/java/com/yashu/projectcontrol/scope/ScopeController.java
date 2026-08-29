package com.yashu.projectcontrol.scope;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/scopes")
public class ScopeController {

    private final ScopeService service;

    public ScopeController(ScopeService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ScopeService.ScopeView create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateScopeRequest request) {
        return service.create(
                projectId,
                request.parentScopeId(),
                request.scopeType(),
                request.code(),
                request.name(),
                request.description(),
                request.startDate(),
                request.endDate(),
                request.configurationJson());
    }

    @GetMapping
    public List<ScopeService.ScopeView> list(@PathVariable UUID projectId) {
        return service.listByProject(projectId);
    }

    @PostMapping("/{scopeId}/participants")
    @ResponseStatus(HttpStatus.CREATED)
    public ScopeService.ScopeAssignmentView assignParticipant(
            @PathVariable UUID projectId,
            @PathVariable UUID scopeId,
            @Valid @RequestBody AssignParticipantRequest request) {
        return service.assignParticipant(
                projectId,
                scopeId,
                request.projectParticipantId(),
                request.responsibility());
    }

    @PutMapping("/{scopeId}/capabilities/{capabilityCode}")
    public ScopeService.CapabilityView setCapability(
            @PathVariable UUID projectId,
            @PathVariable UUID scopeId,
            @PathVariable String capabilityCode,
            @Valid @RequestBody ConfigureCapabilityRequest request) {
        return service.setCapability(
                projectId,
                scopeId,
                capabilityCode,
                request.enabled(),
                request.configurationJson());
    }

    @GetMapping("/{scopeId}/capabilities")
    public List<ScopeService.CapabilityView> listCapabilities(
            @PathVariable UUID projectId,
            @PathVariable UUID scopeId) {
        return service.listCapabilities(projectId, scopeId);
    }

    public record CreateScopeRequest(
            UUID parentScopeId,
            @NotBlank @Size(max = 80) String scopeType,
            @NotBlank @Size(max = 80) String code,
            @NotBlank @Size(max = 240) String name,
            @Size(max = 1000) String description,
            LocalDate startDate,
            LocalDate endDate,
            String configurationJson) {
    }

    public record AssignParticipantRequest(
            @NotNull UUID projectParticipantId,
            @Size(max = 240) String responsibility) {
    }

    public record ConfigureCapabilityRequest(
            boolean enabled,
            String configurationJson) {
    }
}
