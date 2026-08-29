package com.yashu.projectcontrol.commercial;

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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class CommercialController {

    private final CommercialService service;

    public CommercialController(CommercialService service) {
        this.service = service;
    }

    @PostMapping("/contracts")
    public ResponseEntity<CommercialService.ContractView> createContract(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody CreateContractRequest request) {
        return ResponseEntity.ok(service.createContract(
                principal.userId(), projectId, request.payerParticipantId(), request.payeeParticipantId(),
                request.contractNumber(), request.contractType(), request.currency(), request.originalValue(),
                request.visibilityPolicy()));
    }

    @GetMapping("/contracts")
    public ResponseEntity<List<CommercialService.ContractView>> listContracts(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        return ResponseEntity.ok(service.listContracts(principal.userId(), projectId));
    }

    @GetMapping("/contracts/{contractId}")
    public ResponseEntity<CommercialService.ContractView> getContract(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        return ResponseEntity.ok(service.getContract(principal.userId(), projectId, contractId));
    }

    @PostMapping("/contracts/{contractId}/items")
    public ResponseEntity<CommercialService.ContractItemView> createItem(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody CreateContractItemRequest request) {
        return ResponseEntity.ok(service.createContractItem(
                principal.userId(), projectId, contractId, request.scopeId(), request.itemCode(),
                request.description(), request.valuationMethod(), request.unit(), request.plannedQuantity(),
                request.rate(), request.contractValue(), request.dueDate()));
    }

    @GetMapping("/contracts/{contractId}/items")
    public ResponseEntity<List<CommercialService.ContractItemView>> listItems(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        return ResponseEntity.ok(service.listContractItems(principal.userId(), projectId, contractId));
    }

    @PostMapping("/valuations")
    public ResponseEntity<CommercialService.ValuationView> createValuation(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody CreateValuationRequest request) {
        return ResponseEntity.ok(service.createValuation(
                principal.userId(), projectId, request.contractId(), request.contractItemId(),
                request.valuationNumber(), request.sourceType(), request.sourceReference(),
                request.sourceDocumentRevisionId(), request.currentValue(), request.retention(),
                request.otherDeductions()));
    }

    @GetMapping("/valuations")
    public ResponseEntity<List<CommercialService.ValuationView>> listValuations(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestParam UUID contractId) {
        return ResponseEntity.ok(service.listValuations(principal.userId(), projectId, contractId));
    }

    @PostMapping("/payment-applications")
    public ResponseEntity<CommercialService.PaymentApplicationView> createPaymentApplication(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody CreatePaymentApplicationRequest request) {
        return ResponseEntity.ok(service.createPaymentApplication(
                principal.userId(), projectId, request.contractId(), request.applicationNumber(),
                request.periodFrom(), request.periodTo(), request.dueDate(), request.sourceDocumentRevisionId()));
    }

    @GetMapping("/payment-applications/{applicationId}")
    public ResponseEntity<CommercialService.PaymentApplicationView> getPaymentApplication(
            @PathVariable UUID projectId,
            @PathVariable UUID applicationId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        return ResponseEntity.ok(service.getPaymentApplication(principal.userId(), projectId, applicationId));
    }

    @PostMapping("/payment-applications/{applicationId}/lines")
    public ResponseEntity<CommercialService.PaymentApplicationLineView> addPaymentApplicationLine(
            @PathVariable UUID projectId,
            @PathVariable UUID applicationId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody AddPaymentApplicationLineRequest request) {
        return ResponseEntity.ok(service.addPaymentApplicationLine(
                principal.userId(), projectId, applicationId, request.valuationLineId(), request.claimedValue()));
    }

    @GetMapping("/payment-applications/{applicationId}/lines")
    public ResponseEntity<List<CommercialService.PaymentApplicationLineView>> paymentApplicationLines(
            @PathVariable UUID projectId,
            @PathVariable UUID applicationId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        return ResponseEntity.ok(service.listPaymentApplicationLines(
                principal.userId(), projectId, applicationId));
    }

    @PostMapping("/payment-applications/{applicationId}/submit")
    public ResponseEntity<CommercialService.PaymentApplicationView> submitPaymentApplication(
            @PathVariable UUID projectId,
            @PathVariable UUID applicationId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody VersionRequest request) {
        return ResponseEntity.ok(service.submitPaymentApplication(
                principal.userId(), projectId, applicationId, request.version()));
    }

    @PostMapping("/payment-applications/{applicationId}/certify")
    public ResponseEntity<CommercialService.PaymentApplicationView> certifyPaymentApplication(
            @PathVariable UUID projectId,
            @PathVariable UUID applicationId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody CertifyPaymentApplicationRequest request) {
        return ResponseEntity.ok(service.certifyPaymentApplication(
                principal.userId(), projectId, applicationId, request.version(), request.lines()));
    }

    @PostMapping("/payments")
    public ResponseEntity<CommercialService.PaymentView> recordPayment(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestBody RecordPaymentRequest request) {
        return ResponseEntity.ok(service.recordPayment(
                principal.userId(), projectId, request.paymentApplicationId(), request.paymentReference(),
                request.amount(), request.paidAt(), request.sourceDocumentRevisionId()));
    }

    @GetMapping("/payments")
    public ResponseEntity<List<CommercialService.PaymentView>> listPayments(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestParam UUID contractId) {
        return ResponseEntity.ok(service.listPayments(principal.userId(), projectId, contractId));
    }

    @GetMapping("/payments/{paymentId}/trace")
    public ResponseEntity<CommercialService.PaymentTrace> paymentTrace(
            @PathVariable UUID projectId,
            @PathVariable UUID paymentId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        return ResponseEntity.ok(service.paymentTrace(principal.userId(), projectId, paymentId));
    }

    @GetMapping("/contracts/{contractId}/commercial-summary")
    public ResponseEntity<CommercialService.ContractSummary> contractSummary(
            @PathVariable UUID projectId,
            @PathVariable UUID contractId,
            @AuthenticationPrincipal ProjectControlPrincipal principal) {
        return ResponseEntity.ok(service.contractSummary(principal.userId(), projectId, contractId));
    }

    public record CreateContractRequest(
            UUID payerParticipantId, UUID payeeParticipantId, String contractNumber,
            String contractType, String currency, BigDecimal originalValue, String visibilityPolicy) {}
    public record CreateContractItemRequest(
            UUID scopeId, String itemCode, String description, String valuationMethod, String unit,
            BigDecimal plannedQuantity, BigDecimal rate, BigDecimal contractValue, LocalDate dueDate) {}
    public record CreateValuationRequest(
            UUID contractId, UUID contractItemId, String valuationNumber, String sourceType,
            String sourceReference, UUID sourceDocumentRevisionId, BigDecimal currentValue,
            BigDecimal retention, BigDecimal otherDeductions) {}
    public record CreatePaymentApplicationRequest(
            UUID contractId, String applicationNumber, LocalDate periodFrom, LocalDate periodTo,
            LocalDate dueDate, UUID sourceDocumentRevisionId) {}
    public record AddPaymentApplicationLineRequest(UUID valuationLineId, BigDecimal claimedValue) {}
    public record VersionRequest(long version) {}
    public record CertifyPaymentApplicationRequest(long version, List<CommercialService.CertificationLine> lines) {}
    public record RecordPaymentRequest(
            UUID paymentApplicationId, String paymentReference, BigDecimal amount, Instant paidAt,
            UUID sourceDocumentRevisionId) {}
}
