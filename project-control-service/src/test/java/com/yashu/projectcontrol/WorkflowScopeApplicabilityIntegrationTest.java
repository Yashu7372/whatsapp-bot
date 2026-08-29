package com.yashu.projectcontrol;

import com.yashu.projectcontrol.project.ProjectService;
import com.yashu.projectcontrol.scope.ScopeService;
import com.yashu.projectcontrol.workflow.WorkflowApplicabilityService;
import com.yashu.projectcontrol.workflow.WorkflowService;
import com.yashu.projectcontrol.workspace.WorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("test")
class WorkflowScopeApplicabilityIntegrationTest {

    @Autowired WorkspaceService workspaceService;
    @Autowired ProjectService projectService;
    @Autowired ScopeService scopeService;
    @Autowired WorkflowService workflowService;
    @Autowired WorkflowApplicabilityService applicabilityService;

    @Test
    void workflowApplicabilityIsExplicitExactAndProjectSpecific() {
        var workspace = workspaceService.create("WF-SCOPE-MAP", "Workflow Scope Mapping");
        var project = projectService.create(
                workspace.id(), "BUILDING-A", "Building Project A", null,
                null, null, "AED", "Asia/Dubai");

        var construction = scopeService.create(
                project.id(), null, "STAGE", "CONSTRUCTION", "Construction", null,
                null, null, "{}");
        var mep = scopeService.create(
                project.id(), construction.id(), "DISCIPLINE", "MEP", "MEP", null,
                null, null, "{}");
        var chw = scopeService.create(
                project.id(), mep.id(), "PACKAGE", "CHW", "Chilled Water", null,
                null, null, "{}");
        var civil = scopeService.create(
                project.id(), construction.id(), "DISCIPLINE", "CIVIL", "Civil", null,
                null, null, "{}");
        var architecture = scopeService.create(
                project.id(), construction.id(), "DISCIPLINE", "ARCH", "Architectural", null,
                null, null, "{}");

        scopeService.setCapability(project.id(), mep.id(), "INSPECTION", true, "{}");
        scopeService.setCapability(project.id(), chw.id(), "INSPECTION", true, "{}");
        scopeService.setCapability(project.id(), civil.id(), "INSPECTION", true, "{}");
        scopeService.setCapability(project.id(), architecture.id(), "DOCUMENT_CONTROL", true, "{}");

        var definition = workflowService.createDefinition(
                project.id(), "ITR_APPROVAL", 1, "ITR / Work Verification",
                "WORK_VERIFICATION", "INSPECTION");
        workflowService.addStep(
                definition.id(), 1, "SITE_RAISE", "Site Team Raise",
                "SUBMIT", "{\"responsibility\":\"SITE_TEAM\"}", "{}");
        workflowService.addStep(
                definition.id(), 2, "QCE_VERIFY", "QCE Verification",
                "VERIFY", "{\"responsibility\":\"QCE\"}", "{}");
        workflowService.activateDefinition(definition.id());

        workflowService.setScopeBinding(project.id(), mep.id(), definition.id(), true, "{}");

        assertEquals(List.of(definition.id()), applicabilityService
                .listAvailableDefinitions(project.id(), mep.id()).stream().map(WorkflowService.DefinitionView::id).toList());
        assertTrue(applicabilityService.listAvailableDefinitions(project.id(), civil.id()).isEmpty(),
                "A capable sibling scope must not receive a workflow until explicitly bound");
        assertTrue(applicabilityService.listAvailableDefinitions(project.id(), chw.id()).isEmpty(),
                "A child scope must not inherit a parent binding implicitly");
        assertTrue(applicabilityService.listAvailableDefinitions(project.id(), architecture.id()).isEmpty());

        assertThrows(ResponseStatusException.class, () ->
                workflowService.setScopeBinding(project.id(), architecture.id(), definition.id(), true, "{}"));

        workflowService.setScopeBinding(project.id(), civil.id(), definition.id(), true, "{}");
        assertEquals(List.of(definition.id()), applicabilityService
                .listAvailableDefinitions(project.id(), civil.id()).stream().map(WorkflowService.DefinitionView::id).toList());

        var usedBy = applicabilityService.listDefinitionBindings(project.id(), definition.id());
        assertEquals(2, usedBy.size());
        assertTrue(usedBy.stream().anyMatch(binding -> binding.scopeId().equals(mep.id())));
        assertTrue(usedBy.stream().anyMatch(binding -> binding.scopeId().equals(civil.id())));

        scopeService.setCapability(project.id(), civil.id(), "INSPECTION", false, "{}");
        assertTrue(applicabilityService.listAvailableDefinitions(project.id(), civil.id()).isEmpty(),
                "An enabled binding is not runnable when its required capability is later disabled");

        var otherProject = projectService.create(
                workspace.id(), "CONSULTANCY-B", "Consultancy Project B", null,
                null, null, "AED", "Asia/Dubai");
        var designOnly = scopeService.create(
                otherProject.id(), null, "STAGE", "DESIGN", "Design", null,
                null, null, "{}");
        scopeService.setCapability(otherProject.id(), designOnly.id(), "INSPECTION", true, "{}");

        assertThrows(ResponseStatusException.class, () ->
                workflowService.setScopeBinding(project.id(), designOnly.id(), definition.id(), true, "{}"));
        assertTrue(applicabilityService.listAvailableDefinitions(otherProject.id(), designOnly.id()).isEmpty(),
                "A project without the configured workflow definition has no inferred workflow applicability");
    }
}
