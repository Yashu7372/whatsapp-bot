package com.yashu.projectcontrol.assistant;

import com.yashu.projectcontrol.access.ActorContext;
import com.yashu.projectcontrol.access.ProjectAccessService;
import com.yashu.projectcontrol.document.DocumentService;
import com.yashu.projectcontrol.document.DocumentWorkflowLinkRepository;
import com.yashu.projectcontrol.evidence.DocumentEvidenceService;
import com.yashu.projectcontrol.scope.ScopeService;
import com.yashu.projectcontrol.workflow.WorkflowService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.DOCUMENT_VIEW;

/**
 * Role-aware Project Control assistant application service.
 *
 * <p>All authorization and context assembly happens before the optional LLM worker is called.
 * The worker can summarize/explain the resulting context but cannot mutate Project Control.</p>
 */
@Service
public class ProjectControlAssistantService {

    private final WorkflowService workflowService;
    private final ProjectAccessService accessService;
    private final DocumentWorkflowLinkRepository documentWorkflowRepository;
    private final DocumentService documentService;
    private final DocumentEvidenceService evidenceService;
    private final ScopeService scopeService;
    private final ObjectProvider<ProjectControlReasoningWorker> reasoningWorkerProvider;
    private final ObjectMapper objectMapper;

    public ProjectControlAssistantService(
            WorkflowService workflowService,
            ProjectAccessService accessService,
            DocumentWorkflowLinkRepository documentWorkflowRepository,
            DocumentService documentService,
            DocumentEvidenceService evidenceService,
            ScopeService scopeService,
            ObjectProvider<ProjectControlReasoningWorker> reasoningWorkerProvider,
            ObjectMapper objectMapper) {
        this.workflowService = workflowService;
        this.accessService = accessService;
        this.documentWorkflowRepository = documentWorkflowRepository;
        this.documentService = documentService;
        this.evidenceService = evidenceService;
        this.scopeService = scopeService;
        this.reasoningWorkerProvider = reasoningWorkerProvider;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ReviewerBrief reviewerBrief(UUID userId, UUID workflowInstanceId) {
        ContextPack pack = assembleReviewerContext(userId, workflowInstanceId);
        String deterministic = deterministicSummary(pack);
        ReasonedText reasoned = reason(
                "Prepare a concise reviewer brief. Explain why the actor received this item, what changed, "
                        + "important extracted findings/limitations, prior review history, and the authorized next action. "
                        + "Do not claim compliance or approval unless the context proves it.",
                null,
                pack,
                deterministic);

        return new ReviewerBrief(
                pack.workflow().workflowInstanceId(),
                pack.document().documentId(),
                pack.document().revisionId(),
                pack.evidence() == null ? null : pack.evidence().snapshotId(),
                pack.actor(),
                pack.work(),
                pack.workflow(),
                pack.document(),
                pack.evidence(),
                reasoned.text(),
                reasoned.mode(),
                reasoned.limitations(),
                sourceRefs(pack));
    }

    @Transactional(readOnly = true)
    public AssistantAnswer answer(UUID userId, UUID workflowInstanceId, String question) {
        if (question == null || question.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "question is required");
        }
        ContextPack pack = assembleReviewerContext(userId, workflowInstanceId);
        String fallback = deterministicSummary(pack)
                + " No AI reasoning provider is enabled, so I cannot safely infer an answer beyond these authorized facts.";
        ReasonedText reasoned = reason(
                "Answer the reviewer's question using only the authorized Project Control context. "
                        + "If the requested fact is absent, say that it is not established by the available evidence.",
                question.trim(),
                pack,
                fallback);
        return new AssistantAnswer(
                workflowInstanceId,
                question.trim(),
                reasoned.text(),
                reasoned.mode(),
                reasoned.limitations(),
                sourceRefs(pack));
    }

    private ContextPack assembleReviewerContext(UUID userId, UUID workflowInstanceId) {
        var instance = workflowService.getInstance(workflowInstanceId);
        if (instance.currentStep() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Workflow has no active step requiring reviewer assistance");
        }

        // Stronger than visibility: this brief is for the actor currently allowed to act on the step.
        ActorContext actorContext = accessService.requireWorkflowStepAssignment(
                userId,
                instance.projectId(),
                instance.scopeId(),
                instance.currentStep().assignmentJson());

        var link = documentWorkflowRepository.findByWorkflowInstanceId(workflowInstanceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Reviewer brief currently requires a controlled document linked to the workflow"));
        var document = documentService.get(link.getDocumentId());
        accessService.require(userId, DOCUMENT_VIEW, document.projectId(), document.primaryScopeId());

        List<DocumentService.RevisionView> revisions = documentService.listRevisions(document.id()).stream()
                .sorted(Comparator.comparingInt(DocumentService.RevisionView::sequenceNumber))
                .toList();
        if (revisions.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Controlled document has no revision available for review");
        }
        var currentRevision = revisions.getLast();
        var previousRevision = revisions.size() > 1 ? revisions.get(revisions.size() - 2) : null;

        Optional<DocumentEvidenceService.EvidenceView> evidence =
                evidenceService.latest(userId, document.id(), currentRevision.id());
        var history = workflowService.history(workflowInstanceId);
        String completionAction = workflowService.listDefinitionSteps(instance.workflowDefinitionId()).stream()
                .filter(step -> step.sequence() == instance.currentStep().sequence())
                .map(WorkflowService.StepDefinitionView::completionActionCode)
                .findFirst()
                .orElse(null);
        var scope = scopeService.listByProject(instance.projectId()).stream()
                .filter(candidate -> candidate.id().equals(instance.scopeId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "Workflow references a missing project scope"));

        ActorLens actor = actorLens(actorContext);
        WorkLens work = new WorkLens(scope.id(), scope.code(), scope.name(), scope.scopeType());
        WorkflowLens workflow = new WorkflowLens(
                instance.id(),
                instance.workflowCode(),
                instance.workflowVersion(),
                instance.title(),
                instance.status(),
                instance.currentStep().stepCode(),
                instance.currentStep().stepName(),
                instance.currentStep().activatedAt(),
                completionAction,
                history.actions().stream()
                        .map(action -> new HistoryItem(
                                action.actionType(), action.actionCode(), action.fromStepCode(),
                                action.toStepCode(), action.comment(), action.createdAt()))
                        .toList());
        DocumentLens documentLens = new DocumentLens(
                document.id(), document.documentNumber(), document.documentType(), document.title(),
                currentRevision.id(), currentRevision.revisionCode(), currentRevision.revisionStatus(),
                currentRevision.changeNotes(), currentRevision.contentSha256(),
                previousRevision == null ? null : previousRevision.id(),
                previousRevision == null ? null : previousRevision.revisionCode(),
                previousRevision == null ? null : previousRevision.changeNotes());
        EvidenceLens evidenceLens = evidence.map(value -> new EvidenceLens(
                value.id(), value.extractorCode(), value.extractorVersion(),
                value.inputContentSha256(), value.evidenceJson(), value.createdAt())).orElse(null);

        return new ContextPack(actor, work, workflow, documentLens, evidenceLens);
    }

    private ActorLens actorLens(ActorContext actor) {
        Set<UUID> participatingOrganizations = actor.projectParticipations().stream()
                .map(ActorContext.ProjectParticipation::organizationId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        List<String> organizationResponsibilities = actor.organizationMemberships().stream()
                .filter(membership -> participatingOrganizations.contains(membership.organizationId()))
                .map(ActorContext.OrganizationMembership::responsibilityCode)
                .distinct()
                .toList();
        return new ActorLens(
                actor.userId(),
                actor.workspaceRoles(),
                actor.projectParticipations().stream()
                        .map(ActorContext.ProjectParticipation::partyRole)
                        .distinct()
                        .toList(),
                organizationResponsibilities,
                actor.scopeAssignments().stream()
                        .map(ActorContext.ScopeAssignment::responsibilityCode)
                        .distinct()
                        .toList(),
                actor.scopeAssignments().stream()
                        .map(ActorContext.ScopeAssignment::accessLevel)
                        .distinct()
                        .toList());
    }

    private ReasonedText reason(String task, String question, ContextPack pack, String fallback) {
        ProjectControlReasoningWorker worker = reasoningWorkerProvider.getIfAvailable();
        if (worker == null) {
            return new ReasonedText(fallback, "EVIDENCE_ONLY",
                    List.of("AI reasoning is disabled; response contains deterministic authorized context only."));
        }
        try {
            String text = worker.reason(new ProjectControlReasoningWorker.ReasoningRequest(
                    task, question, toJson(pack)));
            return new ReasonedText(text, "AI_ASSISTED", List.of());
        } catch (RuntimeException ex) {
            return new ReasonedText(fallback, "EVIDENCE_ONLY",
                    List.of("AI reasoning was unavailable; deterministic context was returned instead."));
        }
    }

    private String toJson(ContextPack pack) {
        try {
            return objectMapper.writeValueAsString(pack);
        } catch (Exception ex) {
            throw new IllegalStateException("Could not serialize authorized reviewer context", ex);
        }
    }

    private static String deterministicSummary(ContextPack pack) {
        StringBuilder text = new StringBuilder();
        text.append("Review required: ")
                .append(pack.document().documentNumber())
                .append(" Rev ").append(pack.document().revisionCode())
                .append(" at ").append(pack.workflow().currentStepName()).append(". ")
                .append("You received this because your project/scope assignment matches the active workflow step.");
        if (pack.workflow().allowedCompletionAction() != null) {
            text.append(" Authorized completion action: ").append(pack.workflow().allowedCompletionAction()).append('.');
        }
        if (pack.document().changeNotes() != null) {
            text.append(" Current revision notes: ").append(pack.document().changeNotes()).append('.');
        }
        if (pack.evidence() == null) {
            text.append(" No extractor evidence snapshot is available for this revision.");
        } else {
            text.append(" Extractor evidence is available from ")
                    .append(pack.evidence().extractorCode()).append("/")
                    .append(pack.evidence().extractorVersion()).append('.');
        }
        return text.toString();
    }

    private static List<String> sourceRefs(ContextPack pack) {
        java.util.ArrayList<String> refs = new java.util.ArrayList<>();
        refs.add("workflow:" + pack.workflow().workflowInstanceId());
        refs.add("document:" + pack.document().documentId());
        refs.add("revision:" + pack.document().revisionId());
        if (pack.evidence() != null) refs.add("evidence:" + pack.evidence().snapshotId());
        return List.copyOf(refs);
    }

    private record ReasonedText(String text, String mode, List<String> limitations) {}

    public record ActorLens(
            UUID userId,
            List<String> workspaceRoles,
            List<String> projectPartyRoles,
            List<String> organizationResponsibilities,
            List<String> scopeResponsibilities,
            List<String> scopeAccessLevels) {
    }

    public record WorkLens(UUID scopeId, String scopeCode, String scopeName, String scopeType) {}

    public record HistoryItem(
            String actionType,
            String actionCode,
            String fromStepCode,
            String toStepCode,
            String comment,
            Instant createdAt) {
    }

    public record WorkflowLens(
            UUID workflowInstanceId,
            String workflowCode,
            int workflowVersion,
            String title,
            String status,
            String currentStepCode,
            String currentStepName,
            Instant currentStepActivatedAt,
            String allowedCompletionAction,
            List<HistoryItem> history) {
    }

    public record DocumentLens(
            UUID documentId,
            String documentNumber,
            String documentType,
            String title,
            UUID revisionId,
            String revisionCode,
            String revisionStatus,
            String changeNotes,
            String contentSha256,
            UUID previousRevisionId,
            String previousRevisionCode,
            String previousRevisionChangeNotes) {
    }

    public record EvidenceLens(
            UUID snapshotId,
            String extractorCode,
            String extractorVersion,
            String inputContentSha256,
            String evidenceJson,
            Instant createdAt) {
    }

    public record ContextPack(
            ActorLens actor,
            WorkLens work,
            WorkflowLens workflow,
            DocumentLens document,
            EvidenceLens evidence) {
    }

    public record ReviewerBrief(
            UUID workflowInstanceId,
            UUID documentId,
            UUID revisionId,
            UUID evidenceSnapshotId,
            ActorLens actor,
            WorkLens work,
            WorkflowLens workflow,
            DocumentLens document,
            EvidenceLens evidence,
            String summary,
            String reasoningMode,
            List<String> limitations,
            List<String> sourceRefs) {
    }

    public record AssistantAnswer(
            UUID workflowInstanceId,
            String question,
            String answer,
            String reasoningMode,
            List<String> limitations,
            List<String> sourceRefs) {
    }
}
