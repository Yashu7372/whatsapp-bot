package com.yashu.projectcontrol.participation;

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
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/participants")
public class ParticipationController {

    private final ParticipationService service;

    public ParticipationController(ParticipationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ParticipationService.ParticipantView create(
            @PathVariable UUID projectId,
            @Valid @RequestBody CreateParticipantRequest request) {
        return service.create(
                projectId,
                request.organizationId(),
                request.partyRole(),
                request.parentParticipantId(),
                request.validFrom(),
                request.validTo());
    }

    @GetMapping
    public List<ParticipationService.ParticipantView> list(@PathVariable UUID projectId) {
        return service.listByProject(projectId);
    }

    public record CreateParticipantRequest(
            @NotNull UUID organizationId,
            @NotBlank @Size(max = 80) String partyRole,
            UUID parentParticipantId,
            LocalDate validFrom,
            LocalDate validTo) {
    }
}
