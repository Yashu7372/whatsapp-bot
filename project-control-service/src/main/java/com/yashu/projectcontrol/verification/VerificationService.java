package com.yashu.projectcontrol.verification;

import com.yashu.projectcontrol.access.ActorContext;
import com.yashu.projectcontrol.access.ProjectAccessService;
import com.yashu.projectcontrol.project.ProjectService;
import com.yashu.projectcontrol.scope.ScopeService;
import com.yashu.projectcontrol.workflow.WorkflowService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.SCOPE_MANAGE;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.SCOPE_VIEW;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.WORKFLOW_ACT;
import static com.yashu.projectcontrol.access.ProjectAccessService.AccessAction.WORKFLOW_START;

@Service
public class VerificationService {

    private static final List<String> TERMINAL_DECISIONS = List.of(
            "ACCEPTED", "ACCEPTED_WITH_COMMENTS", "PARTIALLY_ACCEPTED", "REJECTED",
            "RETURNED_FOR_REWORK", "MORE_EVIDENCE_REQUESTED");
    private static final List<String> ACCEPTANCE_DECISIONS = List.of(
            "ACCEPTED", "ACCEPTED_WITH_COMMENTS", "PARTIALLY_ACCEPTED");

    private final VerificationRepository repository;
    private final ProjectService projectService;
    private final ScopeService scopeService;
    private final ProjectAccessService accessService;
    private final WorkflowService workflowService;

    public VerificationService(
            VerificationRepository repository,
            ProjectService projectService,
            ScopeService scopeService,
            ProjectAccessService accessService,
            WorkflowService workflowService) {
        this.repository = repository;
        this.projectService = projectService;
        this.scopeService = scopeService;
        this.accessService = accessService;
        this.workflowService = workflowService;
    }

    @Transactional
    public PackageView createPackage(
            UUID actorUserId,
            UUID projectId,
            UUID scopeId,
            String packageNumber,
            String subjectType,
            UUID submittingOrganizationId,
            UUID parentPackageId) {
        projectService.requireExists(projectId);
        scopeService.requireExistsInProject(projectId, scopeId);
        scopeService.requireEnabledCapability(projectId, scopeId, "VERIFICATION");
        accessService.require(actorUserId, WORKFLOW_START, projectId, scopeId);
        accessService.requireCanRepresentOrganization(actorUserId, projectId, submittingOrganizationId);

        if (parentPackageId != null) {
            var parent = requirePackage(projectId, parentPackageId);
            if (!parent.scopeId().equals(scopeId)) {
                throw bad("Corrected/resubmitted verification must reference a package in the same project scope");
            }
            if (parent.status().equals("DRAFT") || parent.status().equals("SUBMITTED")) {
                throw conflict("A corrected/resubmitted verification can reference only a completed prior attempt");
            }
        }

        return toView(repository.insertPackage(
                projectId, scopeId, code(packageNumber, "packageNumber"), code(subjectType, "subjectType"),
                submittingOrganizationId, parentPackageId, actorUserId));
    }

    @Transactional
    public ItemView addItem(
            UUID actorUserId,
            UUID projectId,
            UUID packageId,
            long expectedVersion,
            String subjectResourceReference,
            BigDecimal claimedProgress,
            BigDecimal claimedQuantity,
            String unit,
            String completionStatement) {
        var verificationPackage = requirePackage(projectId, packageId);
        requireDraftWrite(actorUserId, verificationPackage);

        BigDecimal progress = nullablePercent(claimedProgress, "claimedProgress");
        BigDecimal quantity = nullableNonNegative(claimedQuantity, "claimedQuantity");
        String normalizedUnit = optional(unit);
        if (quantity != null && normalizedUnit == null) {
            throw bad("unit is required when claimedQuantity is supplied");
        }
        if (progress == null && quantity == null && optional(completionStatement) == null) {
            throw bad("A verification item needs claimed progress, claimed quantity, or a completion statement");
        }
        if (repository.touchDraft(packageId, expectedVersion) != 1) {
            throw conflict("Verification package is stale or is no longer DRAFT");
        }
        return toView(repository.insertItem(
                packageId, text(subjectResourceReference, "subjectResourceReference"), progress,
                quantity, normalizedUnit, optional(completionStatement)));
    }

    @Transactional
    public EvidenceView addEvidence(
            UUID actorUserId,
            UUID projectId,
            UUID packageId,
            long expectedVersion,
            UUID documentRevisionId,
            String evidenceType,
            String visibilityScope,
            boolean required) {
        var verificationPackage = requirePackage(projectId, packageId);
        requireDraftWrite(actorUserId, verificationPackage);
        var revision = repository.findRevision(projectId, documentRevisionId);
        if (revision == null) {
            throw bad("documentRevisionId must identify a controlled revision in the same project");
        }
        if (revision.scopeId() != null && !revision.scopeId().equals(verificationPackage.scopeId())) {
            throw bad("Evidence revision belongs to a different primary project scope");
        }
        if (revision.contentSha256() == null || revision.contentSha256().isBlank()) {
            throw bad("Verification evidence must reference a controlled revision with a content hash");
        }
        if (repository.touchDraft(packageId, expectedVersion) != 1) {
            throw conflict("Verification package is stale or is no longer DRAFT");
        }
        return toView(repository.insertEvidence(
                packageId, documentRevisionId, code(evidenceType, "evidenceType"),
                code(visibilityScope, "visibilityScope"), required));
    }

    @Transactional
    public PackageView submit(
            UUID actorUserId,
            UUID projectId,
            UUID packageId,
            long expectedVersion,
            UUID workflowDefinitionId) {
        var verificationPackage = requirePackage(projectId, packageId);
        requireDraftWrite(actorUserId, verificationPackage);
        if (repository.listItems(packageId).isEmpty()) {
            throw bad("Verification package cannot be submitted without subject items");
        }
        if (repository.listEvidence(packageId).isEmpty()) {
            throw bad("Verification package cannot be submitted without controlled evidence");
        }

        var definition = workflowService.listDefinitions(projectId).stream()
                .filter(candidate -> candidate.id().equals(workflowDefinitionId))
                .findFirst()
                .orElseThrow(() -> bad("workflowDefinitionId must belong to the project"));
        if (!definition.requiredCapabilityCode().equals("VERIFICATION")) {
            throw bad("Verification packages must use a workflow whose required capability is VERIFICATION");
        }

        if (repository.submitPackage(packageId, expectedVersion, actorUserId) != 1) {
            throw conflict("Verification package is stale or is no longer DRAFT");
        }
        var workflow = workflowService.start(
                projectId,
                verificationPackage.scopeId(),
                workflowDefinitionId,
                "VERIFICATION_" + verificationPackage.packageNumber(),
                "Verification " + verificationPackage.packageNumber(),
                actorUserId.toString(),
                "{\"verificationPackageId\":\"" + packageId + "\",\"subjectType\":\""
                        + verificationPackage.subjectType() + "\"}");
        repository.insertWorkflowLink(packageId, workflow.id());
        return toView(repository.requirePackage(packageId));
    }

    @Transactional
    public DecisionView decide(
            UUID actorUserId,
            UUID projectId,
            UUID packageId,
            UUID itemId,
            long expectedVersion,
            UUID actorOrganizationId,
            String decision,
            BigDecimal acceptedQuantity,
            BigDecimal rejectedQuantity,
            String unit,
            String comments) {
        var verificationPackage = requirePackage(projectId, packageId);
        if (!verificationPackage.status().equals("SUBMITTED")) {
            throw conflict("Verification decisions can be recorded only while the package is SUBMITTED");
        }
        scopeService.requireEnabledCapability(projectId, verificationPackage.scopeId(), "VERIFICATION");
        accessService.require(actorUserId, WORKFLOW_ACT, projectId, verificationPackage.scopeId());
        accessService.requireCanRepresentOrganization(actorUserId, projectId, actorOrganizationId);

        String outcome = code(decision, "decision");
        if (!TERMINAL_DECISIONS.contains(outcome)) {
            throw bad("Unsupported verification decision: " + outcome);
        }
        UUID workflowId = requireTerminalWorkflowActor(verificationPackage, actorUserId, outcome);

        VerificationRepository.ItemRow item = null;
        if (itemId != null) {
            item = repository.findItem(itemId)
                    .filter(candidate -> candidate.packageId().equals(packageId))
                    .orElseThrow(() -> bad("verification item must belong to the package"));
            if (repository.latestItemDecision(packageId, itemId) != null) {
                throw conflict("This verification item already has a decision in this attempt; create a corrected child package for rework/resubmission");
            }
        } else if (acceptedQuantity != null || rejectedQuantity != null || unit != null) {
            throw bad("Package-level decision cannot carry item quantity; record item decisions first");
        }

        QuantityDecision quantities = normalizeDecisionQuantities(
                outcome, item, acceptedQuantity, rejectedQuantity, unit);
        if (itemId == null) {
            validatePackageFinalDecision(packageId, outcome);
        }

        VerificationRepository.DecisionRow row;
        if (itemId == null) {
            row = repository.insertDecision(
                    packageId, null, actorUserId, actorOrganizationId, workflowId, outcome,
                    null, null, null, optional(comments), null, expectedVersion);
            if (repository.completePackage(packageId, expectedVersion, outcome) != 1) {
                throw conflict("Verification package is stale or is no longer SUBMITTED");
            }
        } else {
            if (repository.touchSubmittedPackage(packageId, expectedVersion) != 1) {
                throw conflict("Verification package is stale or is no longer SUBMITTED");
            }
            row = repository.insertDecision(
                    packageId, itemId, actorUserId, actorOrganizationId, workflowId, outcome,
                    quantities.accepted(), quantities.rejected(), quantities.unit(), optional(comments),
                    null, expectedVersion);
        }
        return toView(row);
    }

    @Transactional
    public MeasurementView createMeasurement(
            UUID actorUserId,
            UUID projectId,
            UUID packageId,
            UUID decisionId,
            BigDecimal measuredQuantity,
            LocalDate periodFrom,
            LocalDate periodTo) {
        var verificationPackage = requirePackage(projectId, packageId);
        if (verificationPackage.status().equals("DRAFT") || verificationPackage.status().equals("SUBMITTED")) {
            throw conflict("Measurement requires a completed verification package");
        }
        scopeService.requireEnabledCapability(projectId, verificationPackage.scopeId(), "QUANTITY_MEASUREMENT");
        ActorContext actor = accessService.require(actorUserId, SCOPE_MANAGE, projectId, verificationPackage.scopeId());
        var decision = repository.findDecision(decisionId)
                .filter(candidate -> candidate.packageId().equals(packageId))
                .orElseThrow(() -> bad("verification decision must belong to the package"));
        if (decision.itemId() == null) {
            throw bad("Measurement requires an item-level verification decision");
        }
        var item = repository.requireItem(decision.itemId());
        var latest = repository.latestItemDecision(packageId, item.id());
        if (latest == null || !latest.id().equals(decision.id())) {
            throw conflict("Measurement can be created only from the latest item verification decision");
        }
        if (!ACCEPTANCE_DECISIONS.contains(decision.decision()) && !decision.decision().equals("REJECTED")) {
            throw conflict("This verification outcome does not establish measurable accepted/rejected truth");
        }
        if (decision.acceptedQuantity() == null || decision.rejectedQuantity() == null || decision.unit() == null) {
            throw bad("Item verification decision does not contain quantity truth");
        }
        if (!actorUserId.equals(decision.actorUserId()) && !actor.workspaceRoles().contains("PROJECT_ADMIN")) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Measurement must be recorded by the verifier who established the quantity decision, or PROJECT_ADMIN");
        }
        BigDecimal measured = nonNegative(measuredQuantity, "measuredQuantity");
        BigDecimal accounted = decision.acceptedQuantity().add(decision.rejectedQuantity());
        if (measured.compareTo(accounted) < 0) {
            throw bad("measuredQuantity cannot be less than accepted + rejected quantity");
        }
        if (periodFrom != null && periodTo != null && periodTo.isBefore(periodFrom)) {
            throw bad("periodTo cannot be before periodFrom");
        }
        String status = decision.acceptedQuantity().signum() > 0
                ? (decision.rejectedQuantity().signum() > 0 ? "PARTIALLY_ACCEPTED" : "ACCEPTED")
                : "REJECTED";
        return toView(repository.insertMeasurement(
                projectId, verificationPackage.scopeId(), item.subjectResourceReference(), packageId, item.id(),
                decision.id(), decision.unit(), periodFrom, periodTo, item.claimedQuantity(), measured,
                decision.acceptedQuantity(), decision.rejectedQuantity(), status,
                decision.actorUserId(), decision.decidedAt()));
    }

    @Transactional(readOnly = true)
    public PackageBundle getPackage(UUID actorUserId, UUID projectId, UUID packageId) {
        var verificationPackage = requirePackage(projectId, packageId);
        accessService.require(actorUserId, SCOPE_VIEW, projectId, verificationPackage.scopeId());
        return bundle(verificationPackage);
    }

    @Transactional(readOnly = true)
    public List<PackageView> listPackages(UUID actorUserId, UUID projectId, UUID scopeId) {
        projectService.requireExists(projectId);
        if (scopeId != null) {
            scopeService.requireExistsInProject(projectId, scopeId);
            accessService.require(actorUserId, SCOPE_VIEW, projectId, scopeId);
        } else {
            accessService.require(actorUserId, ProjectAccessService.AccessAction.PROJECT_VIEW, projectId, null);
        }
        return repository.listPackages(projectId, scopeId).stream()
                .filter(row -> canViewScope(actorUserId, projectId, row.scopeId()))
                .map(VerificationService::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public MeasurementView getMeasurement(UUID actorUserId, UUID projectId, UUID measurementId) {
        var measurement = requireMeasurement(projectId, measurementId);
        accessService.require(actorUserId, SCOPE_VIEW, projectId, measurement.scopeId());
        return toView(measurement);
    }

    @Transactional(readOnly = true)
    public List<MeasurementView> listMeasurements(UUID actorUserId, UUID projectId, UUID scopeId) {
        projectService.requireExists(projectId);
        if (scopeId != null) {
            scopeService.requireExistsInProject(projectId, scopeId);
            accessService.require(actorUserId, SCOPE_VIEW, projectId, scopeId);
        } else {
            accessService.require(actorUserId, ProjectAccessService.AccessAction.PROJECT_VIEW, projectId, null);
        }
        return repository.listMeasurements(projectId, scopeId).stream()
                .filter(row -> canViewScope(actorUserId, projectId, row.scopeId()))
                .map(VerificationService::toView)
                .toList();
    }

    /** Internal typed contract consumed by commercial valuation. */
    @Transactional(readOnly = true)
    public AcceptedMeasurement acceptedMeasurementForValuation(UUID projectId, UUID measurementId) {
        var measurement = requireMeasurement(projectId, measurementId);
        var verificationPackage = requirePackage(projectId, measurement.packageId());
        if (!ACCEPTANCE_DECISIONS.contains(verificationPackage.status())) {
            throw conflict("Measurement does not belong to an accepted verification package");
        }
        if (measurement.acceptedQuantity().signum() <= 0
                || !(measurement.status().equals("ACCEPTED") || measurement.status().equals("PARTIALLY_ACCEPTED"))) {
            throw conflict("Measurement contains no accepted quantity eligible for valuation");
        }
        return new AcceptedMeasurement(
                measurement.id(), measurement.projectId(), measurement.scopeId(), measurement.packageId(),
                measurement.itemId(), measurement.decisionId(), measurement.subjectResourceReference(),
                measurement.unit(), measurement.acceptedQuantity(), measurement.verifiedByUserId(),
                measurement.verifiedAt());
    }

    @Transactional(readOnly = true)
    public TraceView traceForMeasurement(UUID projectId, UUID measurementId) {
        var measurement = requireMeasurement(projectId, measurementId);
        var verificationPackage = requirePackage(projectId, measurement.packageId());
        return new TraceView(
                toView(measurement),
                toView(verificationPackage),
                repository.listItems(verificationPackage.id()).stream().map(VerificationService::toView).toList(),
                repository.listEvidence(verificationPackage.id()).stream().map(VerificationService::toView).toList(),
                repository.listDecisions(verificationPackage.id()).stream().map(VerificationService::toView).toList(),
                repository.workflowInstanceId(verificationPackage.id()));
    }

    @Transactional(readOnly = true)
    public ScopeVerificationSummary scopeSummary(UUID actorUserId, UUID projectId, UUID scopeId) {
        scopeService.requireExistsInProject(projectId, scopeId);
        accessService.require(actorUserId, SCOPE_VIEW, projectId, scopeId);
        List<VerificationRepository.PackageRow> packages = repository.listPackages(projectId, scopeId);
        List<VerificationRepository.MeasurementRow> measurements = repository.listMeasurements(projectId, scopeId);
        BigDecimal accepted = measurements.stream()
                .map(VerificationRepository.MeasurementRow::acceptedQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal rejected = measurements.stream()
                .map(VerificationRepository.MeasurementRow::rejectedQuantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new ScopeVerificationSummary(
                scopeId,
                packages.size(),
                packages.stream().filter(row -> row.status().equals("SUBMITTED")).count(),
                packages.stream().filter(row -> ACCEPTANCE_DECISIONS.contains(row.status())).count(),
                measurements.size(),
                accepted,
                rejected);
    }

    private void requireDraftWrite(UUID actorUserId, VerificationRepository.PackageRow verificationPackage) {
        if (!verificationPackage.status().equals("DRAFT")) {
            throw conflict("Verification package can be edited only while DRAFT");
        }
        scopeService.requireEnabledCapability(
                verificationPackage.projectId(), verificationPackage.scopeId(), "VERIFICATION");
        accessService.require(
                actorUserId, WORKFLOW_START, verificationPackage.projectId(), verificationPackage.scopeId());
        accessService.requireCanRepresentOrganization(
                actorUserId, verificationPackage.projectId(), verificationPackage.submittingOrganizationId());
    }

    private UUID requireTerminalWorkflowActor(
            VerificationRepository.PackageRow verificationPackage,
            UUID actorUserId,
            String decision) {
        UUID workflowId = repository.workflowInstanceId(verificationPackage.id());
        if (workflowId == null) {
            throw conflict("Submitted verification package has no linked verification workflow");
        }
        var workflow = workflowService.getInstance(workflowId);
        if (!workflow.projectId().equals(verificationPackage.projectId())
                || !workflow.scopeId().equals(verificationPackage.scopeId())) {
            throw conflict("Verification workflow does not match package project/scope");
        }
        if (ACCEPTANCE_DECISIONS.contains(decision) && !workflow.status().equals("COMPLETED")) {
            throw conflict("Accepted verification truth requires the configured workflow to be COMPLETED");
        }
        if (!workflow.status().equals("COMPLETED") && !workflow.status().equals("REJECTED")) {
            throw conflict("Verification decision requires a terminal workflow state");
        }
        var actions = workflowService.history(workflowId).actions();
        var terminalActor = actions.isEmpty() ? null : actions.getLast().actorReference();
        if (!actorUserId.toString().equals(terminalActor)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Verification decision must be recorded by the actor who performed the terminal workflow action");
        }
        return workflowId;
    }

    private void validatePackageFinalDecision(UUID packageId, String outcome) {
        List<VerificationRepository.ItemRow> items = repository.listItems(packageId);
        List<VerificationRepository.DecisionRow> itemDecisions = repository.listDecisions(packageId).stream()
                .filter(row -> row.itemId() != null)
                .toList();
        if (ACCEPTANCE_DECISIONS.contains(outcome)) {
            for (var item : items) {
                var decision = itemDecisions.stream()
                        .filter(row -> row.itemId().equals(item.id()))
                        .findFirst()
                        .orElseThrow(() -> bad("Accepted package outcome requires a decision for every verification item"));
                if (!ACCEPTANCE_DECISIONS.contains(decision.decision())) {
                    throw bad("Accepted package outcome cannot include a rejected/rework item decision");
                }
            }
            boolean partialItem = itemDecisions.stream().anyMatch(row ->
                    row.decision().equals("PARTIALLY_ACCEPTED")
                            || (row.rejectedQuantity() != null && row.rejectedQuantity().signum() > 0));
            if (outcome.equals("PARTIALLY_ACCEPTED") && !partialItem) {
                throw bad("PARTIALLY_ACCEPTED package requires at least one partially accepted item");
            }
            if ((outcome.equals("ACCEPTED") || outcome.equals("ACCEPTED_WITH_COMMENTS")) && partialItem) {
                throw bad("Package with rejected/rework quantity must be PARTIALLY_ACCEPTED");
            }
        }
    }

    private QuantityDecision normalizeDecisionQuantities(
            String outcome,
            VerificationRepository.ItemRow item,
            BigDecimal acceptedInput,
            BigDecimal rejectedInput,
            String unitInput) {
        if (item == null) return new QuantityDecision(null, null, null);
        if (item.claimedQuantity() == null) {
            if (acceptedInput != null || rejectedInput != null || unitInput != null) {
                throw bad("Non-quantity verification item cannot carry accepted/rejected quantity");
            }
            return new QuantityDecision(null, null, null);
        }

        BigDecimal claimed = item.claimedQuantity();
        String unit = optional(unitInput) == null ? item.unit() : optional(unitInput);
        if (unit == null || !unit.equalsIgnoreCase(item.unit())) {
            throw bad("Verification decision unit must match the claimed item unit");
        }
        BigDecimal accepted;
        BigDecimal rejected;
        switch (outcome) {
            case "ACCEPTED", "ACCEPTED_WITH_COMMENTS" -> {
                accepted = acceptedInput == null ? claimed : nonNegative(acceptedInput, "acceptedQuantity");
                rejected = rejectedInput == null ? BigDecimal.ZERO : nonNegative(rejectedInput, "rejectedQuantity");
                if (accepted.compareTo(claimed) != 0 || rejected.signum() != 0) {
                    throw bad(outcome + " quantity decision must accept the full claimed quantity");
                }
            }
            case "PARTIALLY_ACCEPTED" -> {
                accepted = nonNegative(acceptedInput, "acceptedQuantity");
                rejected = nonNegative(rejectedInput, "rejectedQuantity");
                if (accepted.signum() <= 0 || rejected.signum() <= 0 || accepted.add(rejected).compareTo(claimed) != 0) {
                    throw bad("PARTIALLY_ACCEPTED quantities must be positive and account for the full claimed quantity");
                }
            }
            case "REJECTED" -> {
                accepted = acceptedInput == null ? BigDecimal.ZERO : nonNegative(acceptedInput, "acceptedQuantity");
                rejected = rejectedInput == null ? claimed : nonNegative(rejectedInput, "rejectedQuantity");
                if (accepted.signum() != 0 || rejected.compareTo(claimed) != 0) {
                    throw bad("REJECTED quantity decision must reject the full claimed quantity");
                }
            }
            default -> {
                if (acceptedInput != null || rejectedInput != null) {
                    throw bad(outcome + " does not establish accepted/rejected quantity truth");
                }
                return new QuantityDecision(null, null, null);
            }
        }
        return new QuantityDecision(scale(accepted), scale(rejected), item.unit());
    }

    private PackageBundle bundle(VerificationRepository.PackageRow verificationPackage) {
        return new PackageBundle(
                toView(verificationPackage),
                repository.listItems(verificationPackage.id()).stream().map(VerificationService::toView).toList(),
                repository.listEvidence(verificationPackage.id()).stream().map(VerificationService::toView).toList(),
                repository.listDecisions(verificationPackage.id()).stream().map(VerificationService::toView).toList(),
                repository.listMeasurements(verificationPackage.projectId(), verificationPackage.scopeId()).stream()
                        .filter(row -> row.packageId().equals(verificationPackage.id()))
                        .map(VerificationService::toView)
                        .toList(),
                repository.workflowInstanceId(verificationPackage.id()));
    }

    private boolean canViewScope(UUID actorUserId, UUID projectId, UUID scopeId) {
        try {
            accessService.require(actorUserId, SCOPE_VIEW, projectId, scopeId);
            return true;
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value() == HttpStatus.FORBIDDEN.value()) return false;
            throw ex;
        }
    }

    private VerificationRepository.PackageRow requirePackage(UUID projectId, UUID packageId) {
        var row = repository.findPackage(packageId)
                .orElseThrow(() -> notFound("Verification package not found: " + packageId));
        if (!row.projectId().equals(projectId)) {
            throw notFound("Verification package not found in project: " + packageId);
        }
        return row;
    }

    private VerificationRepository.MeasurementRow requireMeasurement(UUID projectId, UUID measurementId) {
        var row = repository.findMeasurement(measurementId)
                .orElseThrow(() -> notFound("Measurement not found: " + measurementId));
        if (!row.projectId().equals(projectId)) {
            throw notFound("Measurement not found in project: " + measurementId);
        }
        return row;
    }

    private static PackageView toView(VerificationRepository.PackageRow row) {
        return new PackageView(
                row.id(), row.projectId(), row.scopeId(), row.packageNumber(), row.subjectType(),
                row.submittingOrganizationId(), row.createdByUserId(), row.submittedByUserId(),
                row.status(), row.submittedAt(), row.completedAt(), row.parentPackageId(), row.version());
    }

    private static ItemView toView(VerificationRepository.ItemRow row) {
        return new ItemView(
                row.id(), row.packageId(), row.subjectResourceReference(), row.claimedProgress(),
                row.claimedQuantity(), row.unit(), row.completionStatement());
    }

    private static EvidenceView toView(VerificationRepository.EvidenceRow row) {
        return new EvidenceView(
                row.id(), row.packageId(), row.documentRevisionId(), row.evidenceType(), row.visibilityScope(),
                row.required(), row.documentId(), row.documentNumber(), row.title(), row.revisionCode(),
                row.revisionStatus(), row.contentSha256(), row.scopeId());
    }

    private static DecisionView toView(VerificationRepository.DecisionRow row) {
        return new DecisionView(
                row.id(), row.packageId(), row.itemId(), row.actorUserId(), row.actorOrganizationId(),
                row.workflowInstanceId(), row.decision(), row.acceptedQuantity(), row.rejectedQuantity(),
                row.unit(), row.comments(), row.decidedAt(), row.priorDecisionId(), row.subjectVersion());
    }

    private static MeasurementView toView(VerificationRepository.MeasurementRow row) {
        return new MeasurementView(
                row.id(), row.projectId(), row.scopeId(), row.subjectResourceReference(), row.packageId(),
                row.itemId(), row.decisionId(), row.unit(), row.periodFrom(), row.periodTo(),
                row.submittedQuantity(), row.measuredQuantity(), row.acceptedQuantity(), row.rejectedQuantity(),
                row.status(), row.verifiedByUserId(), row.verifiedAt(), row.version());
    }

    private static String code(String value, String field) {
        return text(value, field).trim().toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) throw bad(field + " is required");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static BigDecimal nullablePercent(BigDecimal value, String field) {
        if (value == null) return null;
        BigDecimal normalized = scale(value);
        if (normalized.signum() < 0 || normalized.compareTo(new BigDecimal("100.0000")) > 0) {
            throw bad(field + " must be between 0 and 100");
        }
        return normalized;
    }

    private static BigDecimal nullableNonNegative(BigDecimal value, String field) {
        return value == null ? null : nonNegative(value, field);
    }

    private static BigDecimal nonNegative(BigDecimal value, String field) {
        if (value == null) throw bad(field + " is required");
        BigDecimal normalized = scale(value);
        if (normalized.signum() < 0) throw bad(field + " cannot be negative");
        return normalized;
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }

    private static ResponseStatusException notFound(String message) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, message);
    }

    private record QuantityDecision(BigDecimal accepted, BigDecimal rejected, String unit) {}

    public record PackageView(
            UUID id, UUID projectId, UUID scopeId, String packageNumber, String subjectType,
            UUID submittingOrganizationId, UUID createdByUserId, UUID submittedByUserId,
            String status, java.time.Instant submittedAt, java.time.Instant completedAt,
            UUID parentPackageId, long version) {}

    public record ItemView(
            UUID id, UUID packageId, String subjectResourceReference, BigDecimal claimedProgress,
            BigDecimal claimedQuantity, String unit, String completionStatement) {}

    public record EvidenceView(
            UUID id, UUID packageId, UUID documentRevisionId, String evidenceType, String visibilityScope,
            boolean required, UUID documentId, String documentNumber, String title, String revisionCode,
            String revisionStatus, String contentSha256, UUID scopeId) {}

    public record DecisionView(
            UUID id, UUID packageId, UUID itemId, UUID actorUserId, UUID actorOrganizationId,
            UUID workflowInstanceId, String decision, BigDecimal acceptedQuantity,
            BigDecimal rejectedQuantity, String unit, String comments, java.time.Instant decidedAt,
            UUID priorDecisionId, long subjectVersion) {}

    public record MeasurementView(
            UUID id, UUID projectId, UUID scopeId, String subjectResourceReference,
            UUID packageId, UUID itemId, UUID decisionId, String unit,
            LocalDate periodFrom, LocalDate periodTo, BigDecimal submittedQuantity,
            BigDecimal measuredQuantity, BigDecimal acceptedQuantity, BigDecimal rejectedQuantity,
            String status, UUID verifiedByUserId, java.time.Instant verifiedAt, long version) {}

    public record PackageBundle(
            PackageView verificationPackage, List<ItemView> items, List<EvidenceView> evidence,
            List<DecisionView> decisions, List<MeasurementView> measurements, UUID workflowInstanceId) {}

    public record AcceptedMeasurement(
            UUID measurementId, UUID projectId, UUID scopeId, UUID verificationPackageId,
            UUID verificationItemId, UUID verificationDecisionId, String subjectResourceReference,
            String unit, BigDecimal acceptedQuantity, UUID verifiedByUserId, java.time.Instant verifiedAt) {}

    public record TraceView(
            MeasurementView measurement, PackageView verificationPackage, List<ItemView> items,
            List<EvidenceView> evidence, List<DecisionView> decisions, UUID workflowInstanceId) {}

    public record ScopeVerificationSummary(
            UUID scopeId, long packageCount, long submittedPackageCount, long acceptedPackageCount,
            long measurementCount, BigDecimal acceptedQuantity, BigDecimal rejectedQuantity) {}
}
