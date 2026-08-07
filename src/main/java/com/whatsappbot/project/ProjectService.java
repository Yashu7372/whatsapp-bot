package com.whatsappbot.project;

import com.whatsappbot.domain.tenant.TenantEntity;
import com.whatsappbot.domain.tenant.TenantRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/** Projects and the companies taking part in them. */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ProjectParticipantRepository participantRepository;
    private final OrganizationRepository organizationRepository;
    private final TenantRepository tenantRepository;
    private final ProjectProperties properties;

    // ── Projects ───────────────────────────────────────────────────────────

    @Transactional
    public ProjectEntity create(UUID tenantId, CreateProjectRequest req) {
        String code = normaliseCode(req.projectCode());
        if (projectRepository.existsByTenantIdAndProjectCodeIgnoreCase(tenantId, code)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "A project with code '" + code + "' already exists");
        }

        TenantEntity tenant = tenantRepository.findById(tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Tenant not found"));

        ProjectEntity project = new ProjectEntity();
        project.setTenant(tenant);
        project.setName(req.name());
        project.setProjectCode(code);
        project.setDescription(req.description());
        project.setContractValue(req.contractValue());
        project.setCurrency(req.currency() != null ? req.currency() : properties.getDefaultCurrency());
        project.setRetentionPercent(req.retentionPercent() != null
                ? req.retentionPercent()
                : properties.getDefaultRetentionPercent());
        project.setStatus(properties.getDefaultStatus());
        project.setStartDate(req.startDate());
        project.setEndDate(req.endDate());

        ProjectEntity saved = projectRepository.save(project);
        log.info("Project created. id={} code={} tenant={}", saved.getId(), code, tenantId);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<ProjectEntity> list(UUID tenantId, String status) {
        return status != null && !status.isBlank()
                ? projectRepository.findAllByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, status.toUpperCase())
                : projectRepository.findAllByTenantIdOrderByCreatedAtDesc(tenantId);
    }

    @Transactional(readOnly = true)
    public ProjectEntity get(UUID tenantId, UUID projectId) {
        return projectRepository.findByIdAndTenantId(projectId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Project not found: " + projectId));
    }

    @Transactional
    public ProjectEntity update(UUID tenantId, UUID projectId, UpdateProjectRequest req) {
        ProjectEntity project = get(tenantId, projectId);
        if (req.name() != null) project.setName(req.name());
        if (req.description() != null) project.setDescription(req.description());
        if (req.contractValue() != null) project.setContractValue(req.contractValue());
        if (req.retentionPercent() != null) project.setRetentionPercent(req.retentionPercent());
        if (req.status() != null) project.setStatus(req.status().toUpperCase());
        if (req.startDate() != null) project.setStartDate(req.startDate());
        if (req.endDate() != null) project.setEndDate(req.endDate());
        return projectRepository.save(project);
    }

    // ── Participants ───────────────────────────────────────────────────────

    /**
     * Puts a company on a project in a given capacity.
     *
     * <p>A subcontractor must name the participant that engaged it. That link is what lets the
     * chain of responsibility be walked later — without it a subcontractor's submission has no
     * route back to the main contractor answerable for it.
     */
    @Transactional
    public ParticipantView addParticipant(UUID tenantId, UUID projectId, AddParticipantRequest req) {
        ProjectEntity project = get(tenantId, projectId);

        OrganizationEntity org = organizationRepository
                .findByIdAndTenantId(req.organizationId(), tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Organization not found: " + req.organizationId()));

        if (participantRepository.existsByProjectIdAndOrganizationIdAndPartyRole(
                projectId, org.getId(), req.partyRole())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    org.getName() + " is already on this project as " + req.partyRole());
        }

        UUID parentId = resolveParent(tenantId, projectId, req);

        ProjectParticipantEntity participant = new ProjectParticipantEntity();
        participant.setTenant(project.getTenant());
        participant.setProjectId(projectId);
        participant.setOrganization(org);
        participant.setPartyRole(req.partyRole());
        participant.setParentParticipantId(parentId);

        ProjectParticipantEntity saved = participantRepository.save(participant);
        log.info("Participant added. project={} org={} role={}", projectId, org.getId(), req.partyRole());
        return toView(saved);
    }

    private UUID resolveParent(UUID tenantId, UUID projectId, AddParticipantRequest req) {
        if (req.partyRole() != PartyRole.SUBCONTRACTOR) {
            // Only a subcontractor hangs off another party; anything else is engaged directly.
            return null;
        }
        if (req.parentParticipantId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A subcontractor must reference the participant that engaged it");
        }
        ProjectParticipantEntity parent = participantRepository
                .findByIdAndTenantId(req.parentParticipantId(), tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Parent participant not found: " + req.parentParticipantId()));
        if (!parent.getProjectId().equals(projectId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "The engaging participant belongs to a different project");
        }
        return parent.getId();
    }

    /**
     * Mapped here rather than in the controller: {@code organization} is a LAZY association and
     * open-in-view is false, so reading the company name after this transaction closed would
     * throw.
     */
    @Transactional(readOnly = true)
    public List<ParticipantView> listParticipants(UUID tenantId, UUID projectId, boolean activeOnly) {
        get(tenantId, projectId);
        List<ProjectParticipantEntity> participants = activeOnly
                ? participantRepository.findAllByTenantIdAndProjectIdAndActiveTrueOrderByPartyRoleAsc(
                        tenantId, projectId)
                : participantRepository.findAllByTenantIdAndProjectIdOrderByPartyRoleAsc(tenantId, projectId);
        return participants.stream().map(ProjectService::toView).toList();
    }

    /** The companies engaged by the given participant — a contractor's subcontractors. */
    @Transactional(readOnly = true)
    public List<ParticipantView> listEngagedBy(UUID tenantId, UUID participantId) {
        return participantRepository.findAllByTenantIdAndParentParticipantId(tenantId, participantId)
                .stream().map(ProjectService::toView).toList();
    }

    static ParticipantView toView(ProjectParticipantEntity p) {
        return new ParticipantView(p.getId(), p.getProjectId(), p.getOrganization().getId(),
                p.getOrganization().getName(), p.getOrganization().getOrgCode(), p.getPartyRole(),
                p.getParentParticipantId(), p.isActive(), p.getCreatedAt());
    }

    public record ParticipantView(UUID id, UUID projectId, UUID organizationId,
                                   String organizationName, String organizationCode,
                                   PartyRole partyRole, UUID parentParticipantId,
                                   boolean active, java.time.LocalDateTime createdAt) {}

    @Transactional
    public void removeParticipant(UUID tenantId, UUID participantId) {
        ProjectParticipantEntity participant = participantRepository
                .findByIdAndTenantId(participantId, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Participant not found: " + participantId));

        // Removing a party that others were engaged under would orphan them, so refuse rather
        // than silently detaching the chain.
        if (!participantRepository.findAllByTenantIdAndParentParticipantId(tenantId, participantId).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This participant has subcontractors engaged under it; remove those first");
        }
        participant.setActive(false);
        participantRepository.save(participant);
    }

    /** Whether a company is entitled to see a project's documents. */
    @Transactional(readOnly = true)
    public boolean isParticipant(UUID tenantId, UUID projectId, UUID organizationId) {
        if (organizationId == null) {
            return false;
        }
        return participantRepository.existsByTenantIdAndProjectIdAndOrganizationIdAndActiveTrue(
                tenantId, projectId, organizationId);
    }

    private String normaliseCode(String code) {
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "projectCode is required");
        }
        return code.trim().toUpperCase();
    }

    public record CreateProjectRequest(String name, String projectCode, String description,
                                        java.math.BigDecimal contractValue, String currency,
                                        java.math.BigDecimal retentionPercent,
                                        java.time.LocalDate startDate, java.time.LocalDate endDate) {}

    public record UpdateProjectRequest(String name, String description,
                                        java.math.BigDecimal contractValue,
                                        java.math.BigDecimal retentionPercent, String status,
                                        java.time.LocalDate startDate, java.time.LocalDate endDate) {}

    public record AddParticipantRequest(UUID organizationId, PartyRole partyRole,
                                         UUID parentParticipantId) {}
}
