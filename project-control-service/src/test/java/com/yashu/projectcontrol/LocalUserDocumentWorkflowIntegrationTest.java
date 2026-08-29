package com.yashu.projectcontrol;

import com.yashu.projectcontrol.access.IdentityService;
import com.yashu.projectcontrol.access.ProjectAccessService;
import com.yashu.projectcontrol.document.DocumentService;
import com.yashu.projectcontrol.document.DocumentWorkflowService;
import com.yashu.projectcontrol.document.LocalDocumentContentStore;
import com.yashu.projectcontrol.organization.OrganizationService;
import com.yashu.projectcontrol.participation.ParticipationService;
import com.yashu.projectcontrol.project.ProjectService;
import com.yashu.projectcontrol.scope.ScopeService;
import com.yashu.projectcontrol.workflow.AuthorizedWorkflowExecutionService;
import com.yashu.projectcontrol.workflow.WorkflowService;
import com.yashu.projectcontrol.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;

import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.DOCUMENT_CONTENT_VIEW;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.DOCUMENT_SUBMIT;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.DOCUMENT_VIEW;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.SCOPE_VIEW;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.WORKFLOW_ACT;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.WORKFLOW_CONFIGURE;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.WORKFLOW_START;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessOutcome.ALLOW;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessOutcome.DENY;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
@ActiveProfiles("test")
class LocalUserDocumentWorkflowIntegrationTest {

    @Autowired WorkspaceService workspaceService;
    @Autowired OrganizationService organizationService;
    @Autowired ProjectService projectService;
    @Autowired ParticipationService participationService;
    @Autowired ScopeService scopeService;
    @Autowired IdentityService identityService;
    @Autowired ProjectAccessService accessService;
    @Autowired DocumentService documentService;
    @Autowired LocalDocumentContentStore contentStore;
    @Autowired WorkflowService workflowService;
    @Autowired AuthorizedWorkflowExecutionService executionService;
    @Autowired DocumentWorkflowService documentWorkflowService;

    @Test
    void realItrResponsibilitiesAndScopedVisibilityAreEnforced() {
        var workspace = workspaceService.create("LOCAL-USERS", "Local user flow workspace");
        var project = projectService.create(
                workspace.id(), "LOCAL-USERS-A", "Local user flow project", null,
                null, null, "AED", "Asia/Dubai");
        var contractor = organizationService.create("Local Prime Mechanical LLC", "Prime Mechanical");
        var consultant = organizationService.create("Local Meridian Consultants LLC", "Meridian");
        var contractorParticipant = participationService.create(
                project.id(), contractor.id(), "SUBCONTRACTOR", null, null, null);
        var consultantParticipant = participationService.create(
                project.id(), consultant.id(), "CONSULTANT", null, null, null);
        var mep = scopeService.create(
                project.id(), null, "DISCIPLINE", "MEP", "MEP", null,
                null, null, "{}");
        var civil = scopeService.create(
                project.id(), null, "DISCIPLINE", "CIVIL", "Civil", null,
                null, null, "{}");
        scopeService.assignParticipant(project.id(), mep.id(), contractorParticipant.id(), "MEP delivery");
        scopeService.assignParticipant(project.id(), mep.id(), consultantParticipant.id(), "MEP review");
        scopeService.assignParticipant(project.id(), civil.id(), contractorParticipant.id(), "Civil delivery");
        scopeService.setCapability(project.id(), mep.id(), "DOCUMENT_CONTROL", true, "{}");
        scopeService.setCapability(project.id(), mep.id(), "INSPECTION", true, "{}");

        var admin = identityService.createUser("test:admin", "admin@test.demo", "Project Admin");
        identityService.addWorkspaceMembership(admin.id(), workspace.id(), "PROJECT_ADMIN", null, null);

        var site = userFor(identityService, contractor.id(), contractorParticipant.id(), project.id(), mep.id(),
                "test:site", "site@test.demo", "Site Team", "SITE_TEAM", "CONTRIBUTE");
        var qce = userFor(identityService, contractor.id(), contractorParticipant.id(), project.id(), mep.id(),
                "test:qce", "qce@test.demo", "QCE", "QCE", "APPROVE");
        var qcdc = userFor(identityService, contractor.id(), contractorParticipant.id(), project.id(), mep.id(),
                "test:qcdc", "qcdc@test.demo", "QC/DC", "QC_DC", "CONTRIBUTE");
        var inspector = userFor(identityService, consultant.id(), consultantParticipant.id(), project.id(), mep.id(),
                "test:inspector", "inspector@test.demo", "Consultant Inspector", "CONSULTANT_INSPECTOR", "APPROVE");
        var re = userFor(identityService, consultant.id(), consultantParticipant.id(), project.id(), mep.id(),
                "test:re", "re@test.demo", "Consultant RE", "CONSULTANT_RE", "APPROVE");
        var viewer = userFor(identityService, consultant.id(), consultantParticipant.id(), project.id(), mep.id(),
                "test:viewer", "viewer@test.demo", "Scoped Viewer", "VIEWER", "VIEW");

        assertEquals(ALLOW, accessService.decide(site.id(), DOCUMENT_SUBMIT, project.id(), mep.id()).outcome());
        assertEquals(ALLOW, accessService.decide(viewer.id(), DOCUMENT_VIEW, project.id(), mep.id()).outcome());
        assertEquals(ALLOW, accessService.decide(viewer.id(), DOCUMENT_CONTENT_VIEW, project.id(), mep.id()).outcome());
        assertEquals(DENY, accessService.decide(viewer.id(), DOCUMENT_SUBMIT, project.id(), mep.id()).outcome());
        assertEquals(DENY, accessService.decide(viewer.id(), WORKFLOW_ACT, project.id(), mep.id()).outcome());
        assertEquals(DENY, accessService.decide(viewer.id(), SCOPE_VIEW, project.id(), civil.id()).outcome());
        assertEquals(DENY, accessService.decide(site.id(), WORKFLOW_CONFIGURE, project.id(), mep.id()).outcome());

        accessService.requireCanRepresentOrganization(site.id(), project.id(), contractor.id());
        assertThrows(ResponseStatusException.class,
                () -> accessService.requireCanRepresentOrganization(site.id(), project.id(), consultant.id()));

        var document = documentService.create(
                project.id(), mep.id(), contractor.id(), "LOCAL-DOC-001", null,
                "SHOP_DRAWING", "CHW Routing Shop Drawing", "Local access proof",
                "PROJECT", "{\"discipline\":\"MEP\"}");
        byte[] pdf = "%PDF-1.4\n1 0 obj\n<<>>\nendobj\n%%EOF".getBytes(StandardCharsets.US_ASCII);
        var stored = contentStore.storePdf(pdf, "chw-routing-a.pdf");
        var revision = documentService.addRevision(
                document.id(), "A", "Submitted for review", stored.contentUri(), stored.sha256(),
                stored.originalFilename(), stored.mediaType(), stored.sizeBytes());
        assertEquals("A", revision.revisionCode());
        assertArrayEquals(pdf, contentStore.read(stored.contentUri()));

        var definition = workflowService.createDefinition(
                project.id(), "ITR_APPROVAL", 1, "Work Verification / ITR Approval",
                "WORK_VERIFICATION", "INSPECTION");
        addStep(definition.id(), 1, "SITE_TEAM", "Site Team Raise", "SUBMIT", "SITE_TEAM");
        addStep(definition.id(), 2, "QCE_VERIFY", "QCE Verification", "VERIFY", "QCE");
        addStep(definition.id(), 3, "QC_DC_RECEIVE", "QC/DC Receiving", "RECEIVE", "QC_DC");
        addStep(definition.id(), 4, "CONSULTANT_INSPECT", "Consultant Inspector Review", "REVIEW", "CONSULTANT_INSPECTOR");
        addStep(definition.id(), 5, "RE_FINAL_APPROVAL", "Consultant RE Final Approval", "APPROVE", "CONSULTANT_RE");
        workflowService.activateDefinition(definition.id());
        workflowService.setScopeBinding(project.id(), mep.id(), definition.id(), true, "{}");

        assertEquals(ALLOW, accessService.decide(site.id(), WORKFLOW_START, project.id(), mep.id()).outcome());
        var instance = documentWorkflowService.startForDocument(
                site.id(), document.id(), definition.id(), "ITR-001",
                "CHW installation verification", "{\"revision\":\"A\"}");
        assertEquals("SITE_TEAM", instance.currentStep().stepCode());

        instance = executionService.act(site.id(), instance.id(), "COMPLETE_STEP", "SUBMIT", null,
                "Raised by site team", "{}");
        assertEquals("QCE_VERIFY", instance.currentStep().stepCode());
        var qceStepInstanceId = instance.id();
        assertThrows(ResponseStatusException.class, () -> executionService.act(
                site.id(), qceStepInstanceId, "COMPLETE_STEP", "VERIFY", null, "Wrong actor", "{}"));

        instance = executionService.act(qce.id(), instance.id(), "COMPLETE_STEP", "VERIFY", null,
                "QCE verified", "{}");
        assertEquals("QC_DC_RECEIVE", instance.currentStep().stepCode());
        instance = executionService.act(qcdc.id(), instance.id(), "COMPLETE_STEP", "RECEIVE", null,
                "Received by QC/DC", "{}");
        assertEquals("CONSULTANT_INSPECT", instance.currentStep().stepCode());
        instance = executionService.act(inspector.id(), instance.id(), "COMMENT", null, null,
                "Inspection satisfactory", "{}");
        assertEquals("CONSULTANT_INSPECT", instance.currentStep().stepCode());
        instance = executionService.act(inspector.id(), instance.id(), "COMPLETE_STEP", "REVIEW", null,
                "Forwarded to RE", "{}");
        assertEquals("RE_FINAL_APPROVAL", instance.currentStep().stepCode());
        var reStepInstanceId = instance.id();
        assertThrows(ResponseStatusException.class, () -> executionService.act(
                inspector.id(), reStepInstanceId, "COMPLETE_STEP", "APPROVE", null, "Wrong actor", "{}"));
        instance = executionService.act(re.id(), instance.id(), "COMPLETE_STEP", "APPROVE", null,
                "Final approval", "{}");
        assertEquals("COMPLETED", instance.status());
        assertEquals(1, documentWorkflowService.listForDocument(viewer.id(), document.id()).size());
    }

    private IdentityService.UserView userFor(
            IdentityService identityService,
            java.util.UUID organizationId,
            java.util.UUID participantId,
            java.util.UUID projectId,
            java.util.UUID scopeId,
            String subject,
            String email,
            String name,
            String responsibility,
            String accessLevel) {
        var user = identityService.createUser(subject, email, name);
        identityService.addOrganizationMembership(user.id(), organizationId, responsibility, null, null);
        identityService.addScopeAssignment(
                user.id(), projectId, scopeId, participantId, responsibility, accessLevel, null, null);
        return user;
    }

    private void addStep(java.util.UUID definitionId, int sequence, String code, String name,
                         String action, String responsibility) {
        workflowService.addStep(
                definitionId, sequence, code, name, action,
                "{\"responsibility\":\"" + responsibility + "\"}", "{}");
    }
}
