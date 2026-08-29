package com.yashu.projectcontrol;

import com.yashu.projectcontrol.organization.OrganizationService;
import com.yashu.projectcontrol.participation.ParticipationService;
import com.yashu.projectcontrol.portfolio.PortfolioService;
import com.yashu.projectcontrol.project.ProjectService;
import com.yashu.projectcontrol.scope.ScopeService;
import com.yashu.projectcontrol.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class FoundationDomainIntegrationTest {

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private OrganizationService organizationService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ParticipationService participationService;

    @Autowired
    private ScopeService scopeService;

    @Autowired
    private PortfolioService portfolioService;

    @Test
    void oneOrganizationCanParticipateAcrossDifferentClientWorkspacesAndScopes() {
        var workspaceA = workspaceService.create("CLIENT-A", "Client A Workspace");
        var workspaceB = workspaceService.create("CLIENT-B", "Client B Workspace");
        var workspaceC = workspaceService.create("CLIENT-C", "Client C Workspace");

        var clientA = organizationService.create("Client A Developments LLC", "Client A");
        var clientB = organizationService.create("Client B Properties LLC", "Client B");
        var clientC = organizationService.create("Client C Infrastructure PJSC", "Client C");
        var prime = organizationService.create("Prime Mechanical LLC", "Prime Mechanical");

        var projectA = projectService.create(
                workspaceA.id(), "PRJ-A", "Aurelia Creek", null, null, null, "AED", "Asia/Dubai");
        var projectB = projectService.create(
                workspaceB.id(), "PRJ-B", "Horizon Towers", null, null, null, "AED", "Asia/Dubai");
        var projectC = projectService.create(
                workspaceC.id(), "PRJ-C", "Marina Utilities", null, null, null, "AED", "Asia/Dubai");

        participationService.create(projectA.id(), clientA.id(), "CLIENT", null, null, null);
        participationService.create(projectB.id(), clientB.id(), "CLIENT", null, null, null);
        participationService.create(projectC.id(), clientC.id(), "CLIENT", null, null, null);

        var primeA = participationService.create(
                projectA.id(), prime.id(), "SUBCONTRACTOR", null, null, null);
        var primeB = participationService.create(
                projectB.id(), prime.id(), "SUBCONTRACTOR", null, null, null);
        var primeC = participationService.create(
                projectC.id(), prime.id(), "SPECIALIST_CONTRACTOR", null, null, null);

        var constructionA = scopeService.create(
                projectA.id(), null, "STAGE", "CONSTRUCTION", "Construction", null, null, null, "{}");
        var mepA = scopeService.create(
                projectA.id(), constructionA.id(), "DISCIPLINE", "MEP", "MEP", null, null, null, "{}");

        var constructionB = scopeService.create(
                projectB.id(), null, "STAGE", "CONSTRUCTION", "Construction", null, null, null, "{}");
        var hvacB = scopeService.create(
                projectB.id(), constructionB.id(), "DISCIPLINE", "HVAC", "HVAC", null, null, null, "{}");

        var commissioningC = scopeService.create(
                projectC.id(), null, "STAGE", "COMMISSIONING", "Testing and Commissioning", null, null, null, "{}");

        scopeService.assignParticipant(projectA.id(), mepA.id(), primeA.id(), "MEP installation");
        scopeService.assignParticipant(projectB.id(), hvacB.id(), primeB.id(), "HVAC installation");
        scopeService.assignParticipant(projectC.id(), commissioningC.id(), primeC.id(), "Testing and commissioning");

        scopeService.setCapability(projectA.id(), mepA.id(), "DOCUMENT_CONTROL", true, "{}");
        scopeService.setCapability(projectA.id(), mepA.id(), "WORK_PROGRESS", true, "{}");
        scopeService.setCapability(projectA.id(), mepA.id(), "EQUIPMENT_USAGE", true, "{}");

        scopeService.setCapability(projectB.id(), hvacB.id(), "DOCUMENT_CONTROL", true, "{}");
        scopeService.setCapability(projectB.id(), hvacB.id(), "WORK_PROGRESS", true, "{}");

        scopeService.setCapability(projectC.id(), commissioningC.id(), "DOCUMENT_CONTROL", true, "{}");
        scopeService.setCapability(projectC.id(), commissioningC.id(), "VERIFICATION", true, "{}");

        var primePortfolio = portfolioService.getOrganizationPortfolio(prime.id());

        assertEquals(3, primePortfolio.projects().size());
        assertEquals(Set.of("PRJ-A", "PRJ-B", "PRJ-C"),
                primePortfolio.projects().stream()
                        .map(PortfolioService.ProjectPortfolioItem::projectCode)
                        .collect(Collectors.toSet()));

        var projectAPortfolio = primePortfolio.projects().stream()
                .filter(item -> item.projectCode().equals("PRJ-A"))
                .findFirst()
                .orElseThrow();
        assertEquals("SUBCONTRACTOR", projectAPortfolio.partyRole());
        assertEquals(1, projectAPortfolio.scopes().size());
        assertEquals("MEP", projectAPortfolio.scopes().getFirst().scopeCode());
        assertTrue(projectAPortfolio.scopes().getFirst().capabilities().stream()
                .anyMatch(capability -> capability.capabilityCode().equals("EQUIPMENT_USAGE") && capability.enabled()));

        var projectCPortfolio = primePortfolio.projects().stream()
                .filter(item -> item.projectCode().equals("PRJ-C"))
                .findFirst()
                .orElseThrow();
        assertEquals("SPECIALIST_CONTRACTOR", projectCPortfolio.partyRole());
        assertEquals("COMMISSIONING", projectCPortfolio.scopes().getFirst().scopeCode());
        assertTrue(projectCPortfolio.scopes().getFirst().capabilities().stream()
                .noneMatch(capability -> capability.capabilityCode().equals("EQUIPMENT_USAGE")));

        var clientAPortfolio = portfolioService.getOrganizationPortfolio(clientA.id());
        assertEquals(1, clientAPortfolio.projects().size());
        assertEquals("PRJ-A", clientAPortfolio.projects().getFirst().projectCode());
    }
}
