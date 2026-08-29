package com.yashu.projectcontrol.participation;

import com.yashu.projectcontrol.organization.OrganizationService;
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
public class ParticipationService {

    private final ProjectParticipantRepository repository;
    private final ProjectService projectService;
    private final OrganizationService organizationService;

    public ParticipationService(
            ProjectParticipantRepository repository,
            ProjectService projectService,
            OrganizationService organizationService) {
        this.repository = repository;
        this.projectService = projectService;
        this.organizationService = organizationService;
    }

    @Transactional
    public ParticipantView create(
            UUID projectId,
            UUID organizationId,
            String partyRole,
            UUID parentParticipantId,
            LocalDate validFrom,
            LocalDate validTo) {
        projectService.requireExists(projectId);
        organizationService.requireExists(organizationId);

        if (validFrom != null && validTo != null && validTo.isBefore(validFrom)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Participation validTo cannot be before validFrom");
        }

        if (parentParticipantId != null) {
            requireEntity(projectId, parentParticipantId);
        }

        ProjectParticipant participant = repository.save(ProjectParticipant.create(
                projectId,
                organizationId,
                normalizeCode(partyRole),
                parentParticipantId,
                validFrom,
                validTo));
        return toView(participant);
    }

    @Transactional(readOnly = true)
    public ParticipantView get(UUID projectId, UUID participantId) {
        return toView(requireEntity(projectId, participantId));
    }

    @Transactional(readOnly = true)
    public List<ParticipantView> listByProject(UUID projectId) {
        projectService.requireExists(projectId);
        return repository.findByProjectIdOrderByCreatedAtAsc(projectId).stream()
                .map(ParticipationService::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public List<ParticipantView> listByOrganization(UUID organizationId) {
        organizationService.requireExists(organizationId);
        return repository.findByOrganizationIdOrderByCreatedAtAsc(organizationId).stream()
                .map(ParticipationService::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public void requireBelongsToProject(UUID projectId, UUID participantId) {
        requireEntity(projectId, participantId);
    }

    private ProjectParticipant requireEntity(UUID projectId, UUID participantId) {
        return repository.findByIdAndProjectId(participantId, projectId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "Project participant not found in project: " + participantId));
    }

    private static String normalizeCode(String code) {
        return code.trim().toUpperCase(Locale.ROOT);
    }

    private static ParticipantView toView(ProjectParticipant participant) {
        return new ParticipantView(
                participant.getId(),
                participant.getProjectId(),
                participant.getOrganizationId(),
                participant.getPartyRole(),
                participant.getParentParticipantId(),
                participant.getStatus().name(),
                participant.getValidFrom(),
                participant.getValidTo());
    }

    public record ParticipantView(
            UUID id,
            UUID projectId,
            UUID organizationId,
            String partyRole,
            UUID parentParticipantId,
            String status,
            LocalDate validFrom,
            LocalDate validTo) {
    }
}
