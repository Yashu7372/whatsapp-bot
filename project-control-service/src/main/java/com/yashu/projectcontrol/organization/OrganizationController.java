package com.yashu.projectcontrol.organization;

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
@RequestMapping("/api/v1/organizations")
public class OrganizationController {

    private final OrganizationService service;

    public OrganizationController(OrganizationService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrganizationService.OrganizationView create(@Valid @RequestBody CreateOrganizationRequest request) {
        return service.create(request.legalName(), request.displayName());
    }

    @GetMapping("/{organizationId}")
    public OrganizationService.OrganizationView get(@PathVariable UUID organizationId) {
        return service.get(organizationId);
    }

    public record CreateOrganizationRequest(
            @NotBlank @Size(max = 240) String legalName,
            @Size(max = 200) String displayName) {
    }
}
