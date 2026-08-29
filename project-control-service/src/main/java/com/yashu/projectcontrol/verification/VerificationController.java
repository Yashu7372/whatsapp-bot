package com.yashu.projectcontrol.verification;

import com.yashu.projectcontrol.access.ProjectControlPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class VerificationController {

    private final VerificationService service;

    public VerificationController(VerificationService service) {
        this.service = service;
    }

    @PostMapping("/verification-packages")
    public ResponseEntity<VerificationService.PackageView> createPackage(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody CreatePackageRequest request) {
        return ResponseEntity.ok(service.createPackage(
                principal.userId(), projectId, request.scopeId(), request.packageNumber(),
                request.subjectType(), request.submittingOrganizationId(), request.parentPackageId()));
    }

    @GetMapping("/verification-packages")
    public ResponseEntity<List<VerificationService.PackageView>> listPackages(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestParam(required = false) UUID scopeId) {
        return ResponseEntity.ok(service.listPackages(principal.userId(), projectId, scopeId));
    }

    @GetMapping("/verification-packages/{packageId}")
    public ResponseEntity<VerificationService.PackageBundle> getPackage(
            @PathVariable UUID projectId,
            @PathVariable UUID packageId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        return ResponseEntity.ok(service.getPackage(principal.userId(), projectId, packageId));
    }

    @PostMapping("/verification-packages/{packageId}/items")
    public ResponseEntity<VerificationService.ItemView> addItem(
            @PathVariable UUID projectId,
            @PathVariable UUID packageId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody AddItemRequest request) {
        return ResponseEntity.ok(service.addItem(
                principal.userId(), projectId, packageId, request.version(),
                request.subjectResourceReference(), request.claimedProgress(), request.claimedQuantity(),
                request.unit(), request.completionStatement()));
    }

    @PostMapping("/verification-packages/{packageId}/evidence")
    public ResponseEntity<VerificationService.EvidenceView> addEvidence(
            @PathVariable UUID projectId,
            @PathVariable UUID packageId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody AddEvidenceRequest request) {
        return ResponseEntity.ok(service.addEvidence(
                principal.userId(), projectId, packageId, request.version(), request.documentRevisionId(),
                request.evidenceType(), request.visibilityScope(), request.required()));
    }

    @PostMapping("/verification-packages/{packageId}/submit")
    public ResponseEntity<VerificationService.PackageView> submit(
            @PathVariable UUID projectId,
            @PathVariable UUID packageId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody SubmitRequest request) {
        return ResponseEntity.ok(service.submit(
                principal.userId(), projectId, packageId, request.version(), request.workflowDefinitionId()));
    }

    @PostMapping("/verification-packages/{packageId}/decisions")
    public ResponseEntity<VerificationService.DecisionView> decide(
            @PathVariable UUID projectId,
            @PathVariable UUID packageId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody DecisionRequest request) {
        return ResponseEntity.ok(service.decide(
                principal.userId(), projectId, packageId, request.itemId(), request.version(),
                request.actorOrganizationId(), request.decision(), request.acceptedQuantity(),
                request.rejectedQuantity(), request.unit(), request.comments()));
    }

    @PostMapping("/verification-packages/{packageId}/measurements")
    public ResponseEntity<VerificationService.MeasurementView> createMeasurement(
            @PathVariable UUID projectId,
            @PathVariable UUID packageId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody MeasurementRequest request) {
        return ResponseEntity.ok(service.createMeasurement(
                principal.userId(), projectId, packageId, request.decisionId(), request.measuredQuantity(),
                request.periodFrom(), request.periodTo()));
    }

    @GetMapping("/measurements")
    public ResponseEntity<List<VerificationService.MeasurementView>> listMeasurements(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestParam(required = false) UUID scopeId) {
        return ResponseEntity.ok(service.listMeasurements(principal.userId(), projectId, scopeId));
    }

    @GetMapping("/measurements/{measurementId}")
    public ResponseEntity<VerificationService.MeasurementView> getMeasurement(
            @PathVariable UUID projectId,
            @PathVariable UUID measurementId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        return ResponseEntity.ok(service.getMeasurement(principal.userId(), projectId, measurementId));
    }

    @GetMapping("/measurements/{measurementId}/trace")
    public ResponseEntity<VerificationService.TraceView> measurementTrace(
            @PathVariable UUID projectId,
            @PathVariable UUID measurementId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        service.getMeasurement(principal.userId(), projectId, measurementId);
        return ResponseEntity.ok(service.traceForMeasurement(projectId, measurementId));
    }

    public record CreatePackageRequest(
            UUID scopeId, String packageNumber, String subjectType,
            UUID submittingOrganizationId, UUID parentPackageId) {}

    public record AddItemRequest(
            long version, String subjectResourceReference, BigDecimal claimedProgress,
            BigDecimal claimedQuantity, String unit, String completionStatement) {}

    public record AddEvidenceRequest(
            long version, UUID documentRevisionId, String evidenceType,
            String visibilityScope, boolean required) {}

    public record SubmitRequest(long version, UUID workflowDefinitionId) {}

    public record DecisionRequest(
            long version, UUID itemId, UUID actorOrganizationId, String decision,
            BigDecimal acceptedQuantity, BigDecimal rejectedQuantity, String unit, String comments) {}

    public record MeasurementRequest(
            UUID decisionId, BigDecimal measuredQuantity, LocalDate periodFrom, LocalDate periodTo) {}
}
