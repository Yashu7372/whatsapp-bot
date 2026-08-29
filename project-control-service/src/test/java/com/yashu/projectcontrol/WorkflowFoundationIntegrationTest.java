package com.yashu.projectcontrol;

import com.yashu.projectcontrol.project.ProjectService;
import com.yashu.projectcontrol.scope.ScopeService;
import com.yashu.projectcontrol.workflow.WorkflowService;
import com.yashu.projectcontrol.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class WorkflowFoundationIntegrationTest {

    @Autowired
    private WorkspaceService workspaceService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ScopeService scopeService;

    @Autowired
    private WorkflowService workflowService;

    @Test
    void genericWorkflowRunsByScopeCapabilityWithoutIntroducingItrOrDesignApprovalDomains() {
        var workspace = workspaceService.create("WF-FOUNDATION", "Workflow Foundation Workspace");
        var project = projectService.create(
                workspace.id(), "WF-A", "Workflow Foundation Project", null,
                null, null, "AED", "Asia/Dubai");

        var construction = scopeService.create(
                project.id(), null, "STAGE", "CONSTRUCTION", "Construction", null,
                null, null, "{}");
        var mep = scopeService.create(
                project.id(), construction.id(), "DISCIPLINE", "MEP", "MEP", null,
                null, null, "{}");
        var tender = scopeService.create(
                project.id(), null, "STAGE", "TENDER", "Tender", null,
                null, null, "{}");
        var design = scopeService.create(
                project.id(), null, "STAGE", "DESIGN", "Design", null,
                null, null, "{}");

        scopeService.setCapability(project.id(), mep.id(), "INSPECTION", true, "{}");
        scopeService.setCapability(project.id(), design.id(), "DESIGN_REVIEW", true, "{}");

        var itrDefinition = workflowService.createDefinition(
                project.id(), "ITR_APPROVAL", 1, "ITR approval",
                "WORK_VERIFICATION", "INSPECTION");
        workflowService.addStep(
                itrDefinition.id(), 1, "SITE_TEAM_RAISE", "ITR raised by Site team",
                "RAISE", "{\"responsibility\":\"SITE_TEAM\"}", "{}");
        workflowService.addStep(
                itrDefinition.id(), 2, "QCE_VERIFICATION", "QCE verification",
                "VERIFY", "{\"responsibility\":\"QCE\"}", "{}");
        workflowService.addStep(
                itrDefinition.id(), 3, "QC_DC_RECEIVING", "QC DC receiving",
                "RECEIVE", "{\"responsibility\":\"QC_DC\"}", "{}");
        workflowService.addStep(
                itrDefinition.id(), 4, "CONSULTANT_INSPECTOR_REVIEW", "Consultant Inspector comments",
                "REVIEW", "{\"responsibility\":\"CONSULTANT_INSPECTOR\"}", "{}");
        workflowService.addStep(
                itrDefinition.id(), 5, "CONSULTANT_RE_FINAL", "Consultant RE final approval",
                "APPROVE", "{\"responsibility\":\"CONSULTANT_RE\"}", "{}");
        var activeItrDefinition = workflowService.activateDefinition(itrDefinition.id());
        assertEquals("ACTIVE", activeItrDefinition.status());

        workflowService.setScopeBinding(project.id(), mep.id(), itrDefinition.id(), true, "{}");
        assertThrows(ResponseStatusException.class, () ->
                workflowService.setScopeBinding(project.id(), tender.id(), itrDefinition.id(), true, "{}"));

        var itr = workflowService.start(
                project.id(), mep.id(), itrDefinition.id(), "ITR-044",
                "Zone B CHW inspection request", "site-team:user-01",
                "{\"location\":\"ZONE-B\",\"workReference\":\"CHW-005\"}");
        assertEquals("RUNNING", itr.status());
        assertEquals("ITR-044", itr.businessKey());
        assertEquals("WORK_VERIFICATION", itr.purposeCode());
        assertEquals("INSPECTION", itr.requiredCapabilityCode());
        assertEquals("SITE_TEAM_RAISE", itr.currentStep().stepCode());
        assertEquals(1, itr.currentStep().visitNumber());

        itr = complete(itr.id(), "RAISE", "site-team:user-01", null);
        assertEquals("QCE_VERIFICATION", itr.currentStep().stepCode());

        itr = complete(itr.id(), "VERIFY", "qce:user-02", "Verified internally");
        assertEquals("QC_DC_RECEIVING", itr.currentStep().stepCode());

        itr = complete(itr.id(), "RECEIVE", "qc-dc:user-03", "Received and submitted");
        assertEquals("CONSULTANT_INSPECTOR_REVIEW", itr.currentStep().stepCode());

        workflowService.act(
                itr.id(), "COMMENT", "COMMENT", null, "consultant-inspector:user-04",
                "Rectify support spacing at Grid B4", "{}");
        itr = workflowService.act(
                itr.id(), "RETURN", "RETURN_FOR_RECTIFICATION", "QCE_VERIFICATION",
                "consultant-inspector:user-04", "Return after inspector comment", "{}");
        assertEquals("QCE_VERIFICATION", itr.currentStep().stepCode());
        assertEquals(2, itr.currentStep().visitNumber());

        itr = complete(itr.id(), "VERIFY", "qce:user-02", "Rectification verified");
        itr = complete(itr.id(), "RECEIVE", "qc-dc:user-03", "Resubmitted to consultant");
        itr = complete(itr.id(), "REVIEW", "consultant-inspector:user-04", "Inspector review complete");
        itr = complete(itr.id(), "APPROVE", "consultant-re:user-05", "Final approval");

        assertEquals("COMPLETED", itr.status());
        assertEquals(null, itr.currentStep());

        var history = workflowService.history(itr.id());
        assertEquals(8, history.steps().size());
        assertEquals(10, history.actions().size());
        assertTrue(history.actions().stream()
                .anyMatch(action -> action.actionType().equals("RETURN")
                        && action.actionCode().equals("RETURN_FOR_RECTIFICATION")
                        && action.toStepCode().equals("QCE_VERIFICATION")));
        assertTrue(history.actions().stream()
                .anyMatch(action -> action.actionType().equals("COMMENT")
                        && action.comment().contains("Grid B4")));

        var designDefinition = workflowService.createDefinition(
                project.id(), "DESIGN_APPROVAL", 1, "Design approval",
                "DESIGN_REVIEW", "DESIGN_REVIEW");
        workflowService.addStep(
                designDefinition.id(), 1, "CONSULTANT_REVIEW", "Consultant review",
                "APPROVE", "{\"responsibility\":\"CONSULTANT\"}", "{}");
        workflowService.activateDefinition(designDefinition.id());
        workflowService.setScopeBinding(project.id(), design.id(), designDefinition.id(), true, "{}");

        var designReview = workflowService.start(
                project.id(), design.id(), designDefinition.id(), "DESIGN-021",
                "Structural design review", "design-team:user-10", "{}");
        designReview = complete(designReview.id(), "APPROVE", "consultant:user-11", "Approved");
        assertEquals("COMPLETED", designReview.status());
    }

    private WorkflowService.InstanceView complete(
            java.util.UUID instanceId, String actionCode, String actorReference, String comment) {
        return workflowService.act(
                instanceId, "COMPLETE_STEP", actionCode, null,
                actorReference, comment, "{}");
    }
}
