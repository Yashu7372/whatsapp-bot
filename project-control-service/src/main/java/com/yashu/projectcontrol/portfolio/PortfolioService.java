package com.yashu.projectcontrol.portfolio;

import com.yashu.projectcontrol.organization.OrganizationService;
import com.yashu.projectcontrol.participation.ParticipationService;
import com.yashu.projectcontrol.project.ProjectService;
import com.yashu.projectcontrol.scope.ScopeService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
public class PortfolioService {

    private final OrganizationService organizationService;
    private final ParticipationService participationService;
    private final ProjectService projectService;
    private final ScopeService scopeService;

    public PortfolioService(
            OrganizationService organizationService,
            ParticipationService participationService,
            ProjectService projectService,
            ScopeService scopeService) {
        this.organizationService = organizationService;
        this.participationService = participationService;
        this.projectService = projectService;
        this.scopeService = scopeService;
    }

    @Transactional(readOnly = true)
    public OrganizationPortfolioView getOrganizationPortfolio(UUID organizationId) {
        OrganizationService.OrganizationView organization = organizationService.get(organizationId);

        List<ProjectPortfolioItem> projects = participationService.listByOrganization(organizationId).stream()
                .map(participation -> {
                    ProjectService.ProjectView project = projectService.get(participation.projectId());
                    List<ScopeService.ScopeAssignmentView> scopes =
                            scopeService.listAssignmentsForParticipant(participation.id());
                    return new ProjectPortfolioItem(
                            participation.id(),
                            project.id(),
                            project.workspaceId(),
                            project.code(),
                            project.name(),
                            project.status(),
                            participation.partyRole(),
                            scopes);
                })
                .toList();

        return new OrganizationPortfolioView(
                organization.id(),
                organization.legalName(),
                organization.displayName(),
                projects);
    }

    public record OrganizationPortfolioView(
            UUID organizationId,
            String legalName,
            String displayName,
            List<ProjectPortfolioItem> projects) {
    }

    public record ProjectPortfolioItem(
            UUID projectParticipantId,
            UUID projectId,
            UUID workspaceId,
            String projectCode,
            String projectName,
            String projectStatus,
            String partyRole,
            List<ScopeService.ScopeAssignmentView> scopes) {
    }
}
