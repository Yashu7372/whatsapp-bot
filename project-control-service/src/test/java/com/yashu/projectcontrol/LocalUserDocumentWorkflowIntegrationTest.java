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
    @Autowired DocumentWorkflowService documentWorkflowService;

    @Test
    void submitterReviewerAndViewerExerciseOneTypedDocumentWorkflow() {
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
        var scope = scopeService.create(
                project.id(), null, "DISCIPLINE", "MEP", "MEP", null,
                null, null, "{}");
        scopeService.assignParticipant(project.id(), scope.id(), contractorParticipant.id(), "MEP delivery");
        scopeService.assignParticipant(project.id(), scope.id(), consultantParticipant.id(), "MEP review");
        scopeService.setCapability(project.id(), scope.id(), "DOCUMENT_CONTROL", true, "{}");
        scopeService.setCapability(project.id(), scope.id(), "INSPECTION", true, "{}");

        var admin = identityService.createUser("local:admin", "admin@local.demo", "Project Admin");
        identityService.addWorkspaceMembership(admin.id(), workspace.id(), "PROJECT_ADMIN", null, null);

        var submitter = identityService.createUser("local:submitter", "submitter@local.demo", "Site Submitter");
        identityService.addOrganizationMembership(submitter.id(), contractor.id(), "SITE_ENGINEER", null, null);
        identityService.addScopeAssignment(
                submitter.id(), project.id(), scope.id(), contractorParticipant.id(),
                "SITE_ENGINEER", "CONTRIBUTE", null, null);

        var reviewer = identityService.createUser("local:reviewer", "reviewer@local.demo", "Consultant Reviewer");
        identityService.addOrganizationMembership(reviewer.id(), consultant.id(), "CONSULTANT_RE", null, null);
        identityService.addScopeAssignment(
                reviewer.id(), project.id(), scope.id(), consultantParticipant.id(),
                "CONSULTANT_RE", "APPROVE", null, null);

        var viewer = identityService.createUser("local:viewer", "viewer@local.demo", "Read-only Viewer");
        identityService.addWorkspaceMembership(viewer.id(), workspace.id(), "PROJECT_VIEWER", null, null);

        assertEquals(ALLOW, accessService.decide(submitter.id(), DOCUMENT_SUBMIT, project.id(), scope.id()).outcome());
        assertEquals(ALLOW, accessService.decide(submitter.id(), WORKFLOW_START, project.id(), scope.id()).outcome());
        assertEquals(DENY, accessService.decide(submitter.id(), WORKFLOW_CONFIGURE, project.id(), null).outcome());
        assertEquals(ALLOW, accessService.decide(reviewer.id(), WORKFLOW_ACT, project.id(), scope.id()).outcome());
        assertEquals(ALLOW, accessService.decide(viewer.id(), DOCUMENT_VIEW, project.id(), scope.id()).outcome());
        assertEquals(ALLOW, accessService.decide(viewer.id(), DOCUMENT_CONTENT_VIEW, project.id(), scope.id()).outcome());
        assertEquals(DENY, accessService.decide(viewer.id(), DOCUMENT_SUBMIT, project.id(), scope.id()).outcome());
        assertEquals(DENY, accessService.decide(viewer.id(), WORKFLOW_ACT, project.id(), scope.id()).outcome());

        var document = documentService.create(
                project.id(), scope.id(), contractor.id(), "LOCAL-DOC-001", null,
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
                project.id(), "DOCUMENT_REVIEW", 1, "Document review",
                "DOCUMENT_REVIEW", "INSPECTION");
        workflowService.addStep(definition.id(), 1, "SUBMIT", "Submit document", "SUBMIT", "{}", "{}");
        workflowService.addStep(definition.id(), 2, "REVIEW", "Reviewer decision", "APPROVE", "{}", "{}");
        workflowService.activateDefinition(definition.id());
        workflowService.setScopeBinding(project.id(), scope.id(), definition.id(), true, "{}");

        assertThrows(ResponseStatusException.class, () -> documentWorkflowService.startForDocument(
                viewer.id(), document.id(), definition.id(), "DOC-REVIEW-VIEWER",
                "Viewer must not start", "{}"));

        var instance = documentWorkflowService.startForDocument(
                submitter.id(), document.id(), definition.id(), "DOC-REVIEW-001",
                "Review CHW routing shop drawing", "{\"revision\":\"A\"}");
        assertEquals(1, documentWorkflowService.listForDocument(viewer.id(), document.id()).size());
        assertEquals("SUBMIT", instance.currentStep().stepCode());

        accessService.require(submitter.id(), WORKFLOW_ACT, project.id(), scope.id());
        instance = workflowService.act(
                instance.id(), "COMPLETE_STEP", "SUBMIT", null,
                submitter.id().toString(), "Submitted for consultant review", "{}");
        assertEquals("REVIEW", instance.currentStep().stepCode());

        assertThrows(ResponseStatusException.class,
                () -> accessService.require(viewer.id(), WORKFLOW_ACT, project.id(), scope.id()));
        accessService.require(reviewer.id(), WORKFLOW_ACT, project.id(), scope.id());
        instance = workflowService.act(
                instance.id(), "COMPLETE_STEP", "APPROVE", null,
                reviewer.id().toString(), "Reviewed and approved", "{}");
        assertEquals("COMPLETED", instance.status());
    }
}
