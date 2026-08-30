package com.yashu.projectcontrol.assistant;

import com.yashu.projectcontrol.access.ActorContext;
import com.yashu.projectcontrol.access.ProjectAccessService;
import com.yashu.projectcontrol.document.DocumentService;
import com.yashu.projectcontrol.document.DocumentWorkflowLink;
import com.yashu.projectcontrol.document.DocumentWorkflowLinkRepository;
import com.yashu.projectcontrol.evidence.DocumentEvidenceService;
import com.yashu.projectcontrol.scope.ScopeService;
import com.yashu.projectcontrol.workflow.WorkflowService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectControlAssistantServiceTest {

    @Test
    void returnsEvidenceOnlyReviewerBriefWhenReasoningProviderIsDisabled() throws Exception {
        WorkflowService workflowService = mock(WorkflowService.class);
        ProjectAccessService accessService = mock(ProjectAccessService.class);
        DocumentWorkflowLinkRepository linkRepository = mock(DocumentWorkflowLinkRepository.class);
        DocumentService documentService = mock(DocumentService.class);
        DocumentEvidenceService evidenceService = mock(DocumentEvidenceService.class);
        ScopeService scopeService = mock(ScopeService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<ProjectControlReasoningWorker> workerProvider = mock(ObjectProvider.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);

        UUID userId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID scopeId = UUID.randomUUID();
        UUID definitionId = UUID.randomUUID();
        UUID workflowInstanceId = UUID.randomUUID();
        UUID stepId = UUID.randomUUID();
        UUID stepDefinitionId = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        UUID revisionId = UUID.randomUUID();
        UUID participantId = UUID.randomUUID();
        UUID organizationId = UUID.randomUUID();

        WorkflowService.StepInstanceView currentStep = new WorkflowService.StepInstanceView(
                stepId, workflowInstanceId, stepDefinitionId, 2,
                "CONSULTANT_REVIEW", "Consultant Review", 1,
                "{\"responsibility\":\"REVIEWER\"}", "ACTIVE", Instant.now(), null);
        WorkflowService.InstanceView instance = new WorkflowService.InstanceView(
                workflowInstanceId, projectId, scopeId, definitionId,
                "SHOP_DRAWING_REVIEW", 1, "DOCUMENT_REVIEW", "DOCUMENT_REVIEW",
                "SD-004-R03", "Review SD-004 Rev 03", "RUNNING", currentStep,
                userId.toString(), Instant.now(), null, "{}");
        when(workflowService.getInstance(workflowInstanceId)).thenReturn(instance);

        ActorContext actor = new ActorContext(
                userId, UUID.randomUUID(), projectId, scopeId,
                List.of(),
                List.of(new ActorContext.OrganizationMembership(organizationId, "MECHANICAL_ENGINEER")),
                List.of(new ActorContext.ProjectParticipation(participantId, organizationId, "CONSULTANT", null)),
                List.of(new ActorContext.ScopeAssignment(
                        UUID.randomUUID(), scopeId, participantId, "REVIEWER", "APPROVE")),
                true);
        when(accessService.requireWorkflowStepAssignment(
                userId, projectId, scopeId, currentStep.assignmentJson())).thenReturn(actor);

        DocumentWorkflowLink link = mock(DocumentWorkflowLink.class);
        when(link.getDocumentId()).thenReturn(documentId);
        when(linkRepository.findByWorkflowInstanceId(workflowInstanceId)).thenReturn(Optional.of(link));

        DocumentService.DocumentView document = new DocumentService.DocumentView(
                documentId, projectId, scopeId, organizationId,
                "SD-004", "EXTERNAL", null, "SHOP_DRAWING", "CHW Level 05",
                null, null, "{}", "ACTIVE", 1, "03", Instant.now(), Instant.now());
        when(documentService.get(documentId)).thenReturn(document);

        DocumentService.RevisionView revision = new DocumentService.RevisionView(
                revisionId, documentId, projectId, 1, "03", "CURRENT",
                "Address consultant comments", null,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "SD-004-R03.pdf", "application/pdf", 1024L, Instant.now());
        when(documentService.listRevisions(documentId)).thenReturn(List.of(revision));
        when(evidenceService.latest(userId, documentId, revisionId)).thenReturn(Optional.empty());

        when(workflowService.history(workflowInstanceId)).thenReturn(
                new WorkflowService.HistoryView(workflowInstanceId, List.of(currentStep), List.of()));
        when(workflowService.listDefinitionSteps(definitionId)).thenReturn(List.of(
                new WorkflowService.StepDefinitionView(
                        stepDefinitionId, definitionId, 2, "CONSULTANT_REVIEW",
                        "Consultant Review", "APPROVE",
                        currentStep.assignmentJson(), "{}")));
        when(scopeService.listByProject(projectId)).thenReturn(List.of(
                new ScopeService.ScopeView(
                        scopeId, projectId, null, "WORK_ITEM", "CHW-L05-B",
                        "Level 05 CHW Zone B", null, "ACTIVE",
                        LocalDate.now(), null, "{}")));
        when(workerProvider.getIfAvailable()).thenReturn(null);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        ProjectControlAssistantService service = new ProjectControlAssistantService(
                workflowService, accessService, linkRepository, documentService,
                evidenceService, scopeService, workerProvider, objectMapper);

        var brief = service.reviewerBrief(userId, workflowInstanceId);

        assertThat(brief.reasoningMode()).isEqualTo("EVIDENCE_ONLY");
        assertThat(brief.summary()).contains("SD-004", "Consultant Review", "APPROVE");
        assertThat(brief.actor().projectPartyRoles()).containsExactly("CONSULTANT");
        assertThat(brief.actor().scopeResponsibilities()).containsExactly("REVIEWER");
        assertThat(brief.sourceRefs()).contains(
                "workflow:" + workflowInstanceId,
                "document:" + documentId,
                "revision:" + revisionId);
        assertThat(brief.limitations()).isNotEmpty();
    }
}
