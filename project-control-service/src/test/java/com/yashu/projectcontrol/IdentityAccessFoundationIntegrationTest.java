package com.yashu.projectcontrol;

import com.yashu.projectcontrol.access.IdentityService;
import com.yashu.projectcontrol.access.ProjectAccessService;
import com.yashu.projectcontrol.organization.OrganizationService;
import com.yashu.projectcontrol.participation.ParticipationService;
import com.yashu.projectcontrol.project.ProjectService;
import com.yashu.projectcontrol.scope.ScopeService;
import com.yashu.projectcontrol.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.PROJECT_MANAGE;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.PROJECT_VIEW;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.SCOPE_MANAGE;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.SCOPE_VIEW;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.WORKFLOW_ACT;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessOutcome.ALLOW;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessOutcome.DENY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class IdentityAccessFoundationIntegrationTest {

    @Autowired WorkspaceService workspaceService;
    @Autowired OrganizationService organizationService;
    @Autowired ProjectService projectService;
    @Autowired ParticipationService participationService;
    @Autowired ScopeService scopeService;
    @Autowired IdentityService identityService;
    @Autowired ProjectAccessService accessService;

    @Test
    void actorAuthorityComesFromOrganizationProjectScopeAndExplicitAssignmentContext() {
        var workspace = workspaceService.create("ACCESS-FOUNDATION", "Access Foundation Workspace");
        var project = projectService.create(
                workspace.id(), "ACCESS-A", "Access Project A", null,
                null, null, "AED", "Asia/Dubai");
        var otherProject = projectService.create(
                workspace.id(), "ACCESS-B", "Access Project B", null,
                null, null, "AED", "Asia/Dubai");

        var contractor = organizationService.create("GulfBuild Contracting LLC", "GulfBuild");
        var consultant = organizationService.create("Meridian Engineering Consultants", "Meridian");
        var contractorParticipant = participationService.create(
                project.id(), contractor.id(), "CONTRACTOR", null, null, null);
        participationService.create(project.id(), consultant.id(), "CONSULTANT", null, null, null);

        var construction = scopeService.create(
                project.id(), null, "STAGE", "CONSTRUCTION", "Construction", null,
                null, null, "{}");
        var mep = scopeService.create(
                project.id(), construction.id(), "DISCIPLINE", "MEP", "MEP", null,
                null, null, "{}");
        scopeService.assignParticipant(
                project.id(), mep.id(), contractorParticipant.id(), "MEP delivery responsibility");

        var engineer = identityService.createUser(
                "oidc:gulfbuild:engineer-01", "engineer@gulfbuild.demo", "Arjun Patel");
        identityService.addOrganizationMembership(
                engineer.id(), contractor.id(), "SITE_ENGINEER", null, null);

        var actor = accessService.resolveActor(engineer.id(), project.id(), mep.id());
        assertEquals(1, actor.organizationMemberships().size());
        assertEquals(1, actor.projectParticipations().size());
        assertTrue(actor.organizationAssignedToScope());
        assertTrue(actor.scopeAssignments().isEmpty());

        assertEquals(ALLOW, accessService.decide(engineer.id(), PROJECT_VIEW, project.id(), null).outcome());
        assertEquals(ALLOW, accessService.decide(engineer.id(), SCOPE_VIEW, project.id(), mep.id()).outcome());
        assertEquals(DENY, accessService.decide(engineer.id(), SCOPE_MANAGE, project.id(), mep.id()).outcome());
        assertEquals(DENY, accessService.decide(engineer.id(), WORKFLOW_ACT, project.id(), mep.id()).outcome());

        identityService.addScopeAssignment(
                engineer.id(), project.id(), mep.id(), contractorParticipant.id(),
                "SITE_ENGINEER", "CONTRIBUTE", null, null);
        assertEquals(ALLOW, accessService.decide(engineer.id(), WORKFLOW_ACT, project.id(), mep.id()).outcome());
        assertEquals(DENY, accessService.decide(engineer.id(), SCOPE_MANAGE, project.id(), mep.id()).outcome());

        identityService.addScopeAssignment(
                engineer.id(), project.id(), construction.id(), contractorParticipant.id(),
                "PACKAGE_LEAD", "MANAGE", null, null);
        assertEquals(ALLOW,
                accessService.decide(engineer.id(), SCOPE_MANAGE, project.id(), construction.id()).outcome());

        // Membership in an organization does not grant visibility to every project in the workspace.
        assertEquals(DENY, accessService.decide(engineer.id(), PROJECT_VIEW, otherProject.id(), null).outcome());
        assertThrows(ResponseStatusException.class,
                () -> accessService.require(engineer.id(), PROJECT_VIEW, otherProject.id(), null));
    }

    @Test
    void workspaceBusinessAdminIsExplicitAndOrganizationMembershipCannotBeSpoofed() {
        var workspace = workspaceService.create("ACCESS-ADMIN", "Access Admin Workspace");
        var project = projectService.create(
                workspace.id(), "ACCESS-C", "Access Project C", null,
                null, null, "AED", "Asia/Dubai");
        var contractor = organizationService.create("Prime Mechanical LLC", "Prime Mechanical");
        var consultant = organizationService.create("Control Consultant LLC", "Control Consultant");
        var participant = participationService.create(
                project.id(), contractor.id(), "SUBCONTRACTOR", null, null, null);
        var scope = scopeService.create(
                project.id(), null, "WORK_PACKAGE", "CHW", "CHW Installation", null,
                null, null, "{}");

        var admin = identityService.createUser(
                "oidc:workspace:admin-01", "admin@client.demo", "Workspace Admin");
        identityService.addWorkspaceMembership(
                admin.id(), workspace.id(), "PROJECT_ADMIN", null, null);
        assertEquals(ALLOW, accessService.decide(admin.id(), PROJECT_MANAGE, project.id(), null).outcome());
        assertEquals(ALLOW, accessService.decide(admin.id(), SCOPE_MANAGE, project.id(), scope.id()).outcome());

        var consultantUser = identityService.createUser(
                "oidc:consultant:user-01", "engineer@consultant.demo", "Consultant Engineer");
        identityService.addOrganizationMembership(
                consultantUser.id(), consultant.id(), "CONSULTANT_ENGINEER", null, null);

        assertThrows(ResponseStatusException.class, () -> identityService.addScopeAssignment(
                consultantUser.id(), project.id(), scope.id(), participant.id(),
                "SITE_ENGINEER", "MANAGE", null, null));

        var context = accessService.resolveActor(admin.id(), project.id(), scope.id());
        assertTrue(context.workspaceRoles().contains("PROJECT_ADMIN"));
        assertFalse(context.organizationAssignedToScope());
    }
}
