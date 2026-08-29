package com.yashu.projectcontrol.organization;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

@Service
public class OrganizationService {

    private final OrganizationRepository repository;

    public OrganizationService(OrganizationRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public OrganizationView create(String legalName, String displayName) {
        String normalizedLegalName = legalName.trim();
        String normalizedDisplayName = displayName == null || displayName.isBlank()
                ? normalizedLegalName
                : displayName.trim();

        return toView(repository.save(Organization.create(normalizedLegalName, normalizedDisplayName)));
    }

    @Transactional(readOnly = true)
    public OrganizationView get(UUID id) {
        return toView(requireEntity(id));
    }

    @Transactional(readOnly = true)
    public void requireExists(UUID id) {
        requireEntity(id);
    }

    private Organization requireEntity(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found: " + id));
    }

    private static OrganizationView toView(Organization organization) {
        return new OrganizationView(
                organization.getId(),
                organization.getLegalName(),
                organization.getDisplayName(),
                organization.getStatus().name());
    }

    public record OrganizationView(
            UUID id,
            String legalName,
            String displayName,
            String status) {
    }
}
