package com.yashu.projectcontrol.commercial;

import com.yashu.projectcontrol.financial.FinancialAccessService;
import com.yashu.projectcontrol.project.ProjectService;
import com.yashu.projectcontrol.scope.ScopeService;
import com.yashu.projectcontrol.verification.VerificationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class CommercialService {

    private final CommercialRepository repository;
    private final QuantityValuationRepository quantityValuationRepository;
    private final ProjectService projectService;
    private final ScopeService scopeService;
    private final FinancialAccessService financialAccessService;
    private final VerificationService verificationService;

    public CommercialService(
            CommercialRepository repository,
            QuantityValuationRepository quantityValuationRepository,
            ProjectService projectService,
            ScopeService scopeService,
            FinancialAccessService financialAccessService,
            VerificationService verificationService) {
        this.repository = repository;
        this.quantityValuationRepository = quantityValuationRepository;
        this.projectService = projectService;
        this.scopeService = scopeService;
        this.financialAccessService = financialAccessService;
        this.verificationService = verificationService;
    }

    @Transactional
    public ContractView createContract(
            UUID actorUserId,
            UUID projectId,
            UUID payerParticipantId,
            UUID payeeParticipantId,
            String contractNumber,
            String contractType,
            String currency,
            BigDecimal originalValue,
            String visibilityPolicy) {
        projectService.requireExists(projectId);
        financialAccessService.requireProjectManage(actorUserId, projectId);
        var payer = requireParticipant(projectId, payerParticipantId);
        var payee = requireParticipant(projectId, payeeParticipantId);
        if (payer.id().equals(payee.id()) || payer.organizationId().equals(payee.organizationId())) {
            throw bad("Contract payer and payee must be different project participants/organizations");
        }
        return toView(repository.insertContract(
                projectId, payerParticipantId, payeeParticipantId,
                code(contractNumber, "contractNumber"), code(contractType, "contractType"),
                projectCurrency(projectId, currency), nonNegative(originalValue, "originalValue"),
                visibilityPolicy == null || visibilityPolicy.isBlank()
                        ? "CONTRACT_SHARED" : code(visibilityPolicy, "visibilityPolicy")));
    }

    @Transactional(readOnly = true)
    public List<ContractView> listContracts(UUID actorUserId, UUID projectId) {
        projectService.requireExists(projectId);
        financialAccessService.requireProjectRead(actorUserId, projectId);
        return repository.listContracts(projectId).stream()
                .filter(contract -> canViewContract(actorUserId, contract))
                .map(CommercialService::toView)
                .toList();
    }

    @Transactional(readOnly = true)
    public ContractView getContract(UUID actorUserId, UUID projectId, UUID contractId) {
        var contract = requireContract(projectId, contractId);
        financialAccessService.requireContractView(
                actorUserId, projectId, contract.payerOrganizationId(), contract.payeeOrganizationId());
        return toView(contract);
    }

    @Transactional
    public ContractItemView createContractItem(
            UUID actorUserId,
            UUID projectId,
            UUID contractId,
            UUID scopeId,
            String itemCode,
            String description,
            String valuationMethod,
            String unit,
            BigDecimal plannedQuantity,
            BigDecimal rate,
            BigDecimal contractValue,
            LocalDate dueDate) {
        var contract = requireContract(projectId, contractId);
        financialAccessService.requireProjectManage(actorUserId, projectId);
        if (scopeId != null) scopeService.requireExistsInProject(projectId, scopeId);
        String method = code(valuationMethod, "valuationMethod");
        if (!List.of("QUANTITY_RATE", "MILESTONE", "LUMP_SUM", "PERCENTAGE", "TIME_BASED", "OTHER").contains(method)) {
            throw bad("Unsupported valuation method: " + method);
        }
        BigDecimal normalizedRate = optionalNonNegative(rate, "rate");
        BigDecimal normalizedQuantity = optionalNonNegative(plannedQuantity, "plannedQuantity");
        BigDecimal normalizedValue;
        if (method.equals("QUANTITY_RATE")) {
            if (scopeId == null) {
                throw bad("QUANTITY_RATE contract item requires a project scope for verification/measurement traceability");
            }
            if (normalizedRate == null || normalizedQuantity == null || unit == null || unit.isBlank()) {
                throw bad("QUANTITY_RATE contract item requires unit, plannedQuantity and rate");
            }
            normalizedValue = normalizedQuantity.multiply(normalizedRate).setScale(4, RoundingMode.HALF_UP);
            if (contractValue != null && normalizedValue.compareTo(scale(contractValue)) != 0) {
                throw bad("QUANTITY_RATE contractValue must equal plannedQuantity x rate");
            }
        } else {
            normalizedValue = nonNegative(contractValue, "contractValue");
        }
        if (!contract.currency().equals(projectCurrency(projectId, contract.currency()))) {
            throw bad("Contract currency must match project currency in the current foundation");
        }
        return toView(repository.insertItem(
                contractId, scopeId, code(itemCode, "itemCode"), text(description, "description"), method,
                optional(unit), normalizedQuantity, normalizedRate, normalizedValue, dueDate));
    }

    @Transactional(readOnly = true)
    public List<ContractItemView> listContractItems(UUID actorUserId, UUID projectId, UUID contractId) {
        var contract = requireContract(projectId, contractId);
        financialAccessService.requireContractView(
                actorUserId, projectId, contract.payerOrganizationId(), contract.payeeOrganizationId());
        return repository.listItems(contractId).stream().map(CommercialService::toView).toList();
    }

    /** Backward-compatible non-quantity entry point. */
    @Transactional
    public ValuationView createValuation(
            UUID actorUserId,
            UUID projectId,
            UUID contractId,
            UUID contractItemId,
            String valuationNumber,
            String sourceType,
            String sourceReference,
            UUID sourceDocumentRevisionId,
            BigDecimal currentValue,
            BigDecimal retention,
            BigDecimal otherDeductions) {
        return createValuation(
                actorUserId, projectId, contractId, contractItemId, valuationNumber,
                sourceType, sourceReference, sourceDocumentRevisionId, null,
                currentValue, retention, otherDeductions);
    }

    @Transactional
    public ValuationView createValuation(
            UUID actorUserId,
            UUID projectId,
            UUID contractId,
            UUID contractItemId,
            String valuationNumber,
            String sourceType,
            String sourceReference,
            UUID sourceDocumentRevisionId,
            UUID measurementId,
            BigDecimal currentValue,
            BigDecimal retention,
            BigDecimal otherDeductions) {
        var contract = requireContract(projectId, contractId);
        var item = requireItem(projectId, contractId, contractItemId);
        financialAccessService.requireContractParty(
                actorUserId, projectId, contract.payeeOrganizationId(), "Preparing a valuation");
        if (item.scopeId() != null) {
            scopeService.requireEnabledCapability(projectId, item.scopeId(), "VALUATION");
        }

        BigDecimal value;
        BigDecimal acceptedQuantity = null;
        UUID directEvidenceRevision = sourceDocumentRevisionId;
        String effectiveSourceType;
        String effectiveSourceReference;

        if (item.valuationMethod().equals("QUANTITY_RATE")) {
            if (measurementId == null) {
                throw bad("QUANTITY_RATE valuation requires measurementId from accepted verification truth");
            }
            if (sourceDocumentRevisionId != null || currentValue != null) {
                throw bad("QUANTITY_RATE valuation value/evidence are derived from measurement; do not submit currentValue or direct document evidence");
            }
            var measurement = verificationService.acceptedMeasurementForValuation(projectId, measurementId);
            if (!measurement.scopeId().equals(item.scopeId())) {
                throw bad("Accepted measurement belongs to a different project scope than the contract item");
            }
            if (item.unit() == null || !item.unit().equalsIgnoreCase(measurement.unit())) {
                throw bad("Accepted measurement unit must match the contract item unit");
            }
            BigDecimal alreadyValued = quantityValuationRepository.acceptedQuantityAlreadyValued(contractItemId);
            BigDecimal cumulativeQuantity = alreadyValued.add(measurement.acceptedQuantity());
            if (item.plannedQuantity() != null && cumulativeQuantity.compareTo(item.plannedQuantity()) > 0) {
                throw bad("Cumulative accepted quantity cannot exceed contract item planned quantity");
            }
            acceptedQuantity = scale(measurement.acceptedQuantity());
            value = acceptedQuantity.multiply(item.rate()).setScale(4, RoundingMode.HALF_UP);
            directEvidenceRevision = null;
            effectiveSourceType = "ACCEPTED_MEASUREMENT";
            effectiveSourceReference = "measurement://" + projectId + "/" + measurementId;
        } else {
            if (measurementId != null) {
                throw bad("measurementId is applicable only to QUANTITY_RATE valuation");
            }
            if (sourceDocumentRevisionId == null) {
                throw bad("Non-quantity valuation requires sourceDocumentRevisionId as controlled supporting evidence");
            }
            var evidence = repository.findRevisionEvidence(sourceDocumentRevisionId, projectId);
            if (evidence == null) {
                throw bad("sourceDocumentRevisionId must belong to the same project");
            }
            if (item.scopeId() != null && evidence.scopeId() != null && !item.scopeId().equals(evidence.scopeId())) {
                throw bad("Valuation evidence revision belongs to a different project scope than the contract item");
            }
            value = positive(currentValue, "currentValue");
            effectiveSourceType = code(sourceType, "sourceType");
            effectiveSourceReference = optional(sourceReference);
        }

        BigDecimal prior = repository.listValuations(contractId).stream()
                .filter(existing -> existing.contractItemId().equals(contractItemId))
                .filter(existing -> !existing.status().equals("VOID"))
                .map(CommercialRepository.ValuationRow::currentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal cumulative = prior.add(value);
        if (cumulative.compareTo(item.contractValue()) > 0) {
            throw bad("Cumulative valuation cannot exceed contract item value");
        }
        BigDecimal normalizedRetention = nonNegativeDefault(retention);
        BigDecimal deductions = nonNegativeDefault(otherDeductions);
        BigDecimal eligible = value.subtract(normalizedRetention).subtract(deductions);
        if (eligible.signum() < 0) {
            throw bad("Retention and deductions cannot exceed current valuation value");
        }

        var valuation = repository.insertValuation(
                projectId, contractId, item.scopeId(), contractItemId,
                code(valuationNumber, "valuationNumber"), effectiveSourceType,
                effectiveSourceReference, directEvidenceRevision, item.unit(), acceptedQuantity, item.rate(),
                value, prior, value, cumulative, normalizedRetention, deductions, eligible, actorUserId);
        if (measurementId != null) {
            quantityValuationRepository.linkMeasurement(valuation.id(), measurementId);
        }
        return toView(valuation);
    }

    @Transactional(readOnly = true)
    public List<ValuationView> listValuations(UUID actorUserId, UUID projectId, UUID contractId) {
        var contract = requireContract(projectId, contractId);
        financialAccessService.requireContractView(
                actorUserId, projectId, contract.payerOrganizationId(), contract.payeeOrganizationId());
        return repository.listValuations(contractId).stream().map(this::toView).toList();
    }

    @Transactional
    public PaymentApplicationView createPaymentApplication(
            UUID actorUserId,
            UUID projectId,
            UUID contractId,
            String applicationNumber,
            LocalDate periodFrom,
            LocalDate periodTo,
            LocalDate dueDate,
            UUID sourceDocumentRevisionId) {
        var contract = requireContract(projectId, contractId);
        financialAccessService.requireContractParty(
                actorUserId, projectId, contract.payeeOrganizationId(), "Creating a payment application");
        if (periodFrom != null && periodTo != null && periodTo.isBefore(periodFrom)) {
            throw bad("Payment application periodTo cannot be before periodFrom");
        }
        validateEvidence(projectId, sourceDocumentRevisionId);
        return toView(repository.insertApplication(
                projectId, contractId, code(applicationNumber, "applicationNumber"), periodFrom, periodTo,
                dueDate, sourceDocumentRevisionId, actorUserId));
    }

    @Transactional
    public PaymentApplicationLineView addPaymentApplicationLine(
            UUID actorUserId,
            UUID projectId,
            UUID applicationId,
            UUID valuationLineId,
            BigDecimal claimedValue) {
        var application = requireApplication(projectId, applicationId);
        var contract = requireContract(projectId, application.contractId());
        financialAccessService.requireContractParty(
                actorUserId, projectId, contract.payeeOrganizationId(), "Adding a payment application line");
        if (!application.status().equals("DRAFT")) {
            throw conflict("Payment application lines can be changed only while DRAFT");
        }
        var valuation = repository.findValuation(valuationLineId)
                .orElseThrow(() -> notFound("Valuation line not found: " + valuationLineId));
        if (!valuation.projectId().equals(projectId) || !valuation.contractId().equals(application.contractId())) {
            throw bad("Valuation line must belong to the payment application's project and contract");
        }
        if (valuation.scopeId() != null) {
            scopeService.requireEnabledCapability(projectId, valuation.scopeId(), "IPC");
        }
        BigDecimal claim = positive(claimedValue, "claimedValue");
        BigDecimal alreadyClaimed = repository.previouslyClaimedValue(valuationLineId);
        BigDecimal remainingEligible = valuation.eligibleValue().subtract(alreadyClaimed);
        if (claim.compareTo(remainingEligible) > 0) {
            throw bad("Claimed value exceeds remaining eligible valuation value");
        }
        return toView(repository.insertApplicationLine(applicationId, valuationLineId, claim));
    }

    @Transactional(readOnly = true)
    public PaymentApplicationView getPaymentApplication(
            UUID actorUserId, UUID projectId, UUID applicationId) {
        var app = requireApplication(projectId, applicationId);
        var contract = requireContract(projectId, app.contractId());
        financialAccessService.requireContractView(
                actorUserId, projectId, contract.payerOrganizationId(), contract.payeeOrganizationId());
        return toView(app);
    }

    @Transactional(readOnly = true)
    public List<PaymentApplicationLineView> listPaymentApplicationLines(
            UUID actorUserId, UUID projectId, UUID applicationId) {
        var app = requireApplication(projectId, applicationId);
        var contract = requireContract(projectId, app.contractId());
        financialAccessService.requireContractView(
                actorUserId, projectId, contract.payerOrganizationId(), contract.payeeOrganizationId());
        return repository.listApplicationLines(applicationId).stream().map(CommercialService::toView).toList();
    }

    @Transactional
    public PaymentApplicationView submitPaymentApplication(
            UUID actorUserId,
            UUID projectId,
            UUID applicationId,
            long expectedVersion) {
        var app = requireApplication(projectId, applicationId);
        var contract = requireContract(projectId, app.contractId());
        financialAccessService.requireContractParty(
                actorUserId, projectId, contract.payeeOrganizationId(), "Submitting a payment application");
        if (repository.listApplicationLines(applicationId).isEmpty()) {
            throw bad("Payment application cannot be submitted without valuation-backed lines");
        }
        if (repository.submitApplication(applicationId, expectedVersion, actorUserId) != 1) {
            throw conflict("Payment application is stale or is not DRAFT");
        }
        return toView(repository.requireApplication(applicationId));
    }

    @Transactional
    public PaymentApplicationView certifyPaymentApplication(
            UUID actorUserId,
            UUID projectId,
            UUID applicationId,
            long expectedVersion,
            List<CertificationLine> certifications) {
        var app = requireApplication(projectId, applicationId);
        var contract = requireContract(projectId, app.contractId());
        financialAccessService.requireContractParty(
                actorUserId, projectId, contract.payerOrganizationId(), "Certifying a payment application");
        if (!app.status().equals("SUBMITTED")) {
            throw conflict("Only a SUBMITTED payment application can be certified");
        }
        List<CommercialRepository.ApplicationLineRow> lines = repository.listApplicationLines(applicationId);
        if (certifications == null || certifications.size() != lines.size()) {
            throw bad("Certification must provide a decision for every payment application line");
        }
        for (var line : lines) {
            CertificationLine certification = certifications.stream()
                    .filter(input -> input.valuationLineId().equals(line.valuationLineId()))
                    .findFirst()
                    .orElseThrow(() -> bad("Missing certification for valuation line " + line.valuationLineId()));
            BigDecimal certified = nonNegative(certification.certifiedValue(), "certifiedValue");
            if (certified.compareTo(line.claimedValue()) > 0) {
                throw bad("Certified value cannot exceed claimed value for valuation line " + line.valuationLineId());
            }
            if (repository.setLineCertification(
                    applicationId, line.valuationLineId(), certified, optional(certification.reason())) != 1) {
                throw conflict("Payment application line was already certified or changed concurrently");
            }
        }
        if (repository.certifyApplication(applicationId, expectedVersion, actorUserId) != 1) {
            throw conflict("Payment application is stale or is no longer SUBMITTED");
        }
        return toView(repository.requireApplication(applicationId));
    }

    @Transactional
    public PaymentView recordPayment(
            UUID actorUserId,
            UUID projectId,
            UUID applicationId,
            String paymentReference,
            BigDecimal amount,
            Instant paidAt,
            UUID sourceDocumentRevisionId) {
        var app = requireApplication(projectId, applicationId);
        var contract = requireContract(projectId, app.contractId());
        financialAccessService.requireContractParty(
                actorUserId, projectId, contract.payerOrganizationId(), "Recording payment");
        if (!app.status().equals("CERTIFIED")) {
            throw conflict("Payment can be recorded only against a CERTIFIED payment application");
        }
        validateEvidence(projectId, sourceDocumentRevisionId);
        BigDecimal normalized = positive(amount, "amount");
        BigDecimal certified = app.certifiedAmount() == null ? BigDecimal.ZERO : app.certifiedAmount();
        BigDecimal paid = repository.paidForApplication(applicationId);
        BigDecimal outstanding = certified.subtract(paid);
        if (normalized.compareTo(outstanding) > 0) {
            throw bad("Payment exceeds outstanding certified amount");
        }
        for (var line : repository.listApplicationLines(applicationId)) {
            var valuation = repository.requireValuation(line.valuationLineId());
            if (valuation.scopeId() != null) {
                scopeService.requireEnabledCapability(projectId, valuation.scopeId(), "PAYMENT");
            }
        }
        return toView(repository.insertPayment(
                projectId, contract.id(), applicationId, code(paymentReference, "paymentReference"), normalized,
                contract.currency(), paidAt == null ? Instant.now() : paidAt,
                contract.payerOrganizationId(), contract.payeeOrganizationId(), sourceDocumentRevisionId, actorUserId));
    }

    @Transactional(readOnly = true)
    public List<PaymentView> listPayments(UUID actorUserId, UUID projectId, UUID contractId) {
        var contract = requireContract(projectId, contractId);
        financialAccessService.requireContractView(
                actorUserId, projectId, contract.payerOrganizationId(), contract.payeeOrganizationId());
        return repository.listPayments(contractId).stream().map(CommercialService::toView).toList();
    }

    @Transactional(readOnly = true)
    public ContractSummary contractSummary(UUID actorUserId, UUID projectId, UUID contractId) {
        var contract = requireContract(projectId, contractId);
        financialAccessService.requireContractView(
                actorUserId, projectId, contract.payerOrganizationId(), contract.payeeOrganizationId());
        var row = repository.contractSummary(contractId);
        return new ContractSummary(
                row.contractId(), row.originalValue(), BigDecimal.ZERO, row.originalValue(),
                row.valuedToDate(), row.claimedToDate(), row.certifiedToDate(), row.paidToDate(),
                row.retentionToDate(), row.outstandingCertified());
    }

    @Transactional(readOnly = true)
    public PaymentTrace paymentTrace(UUID actorUserId, UUID projectId, UUID paymentId) {
        var payment = repository.findPayment(paymentId)
                .orElseThrow(() -> notFound("Payment not found: " + paymentId));
        if (!payment.projectId().equals(projectId)) throw notFound("Payment not found in project: " + paymentId);
        var contract = requireContract(projectId, payment.contractId());
        financialAccessService.requireContractView(
                actorUserId, projectId, contract.payerOrganizationId(), contract.payeeOrganizationId());
        var app = requireApplication(projectId, payment.paymentApplicationId());
        var lines = repository.listApplicationLines(app.id()).stream()
                .map(line -> {
                    var valuation = repository.requireValuation(line.valuationLineId());
                    var item = repository.requireItem(valuation.contractItemId());
                    UUID measurementId = quantityValuationRepository.measurementId(valuation.id());
                    if (measurementId != null) {
                        var verificationTrace = verificationService.traceForMeasurement(projectId, measurementId);
                        EvidenceView primaryEvidence = verificationTrace.evidence().isEmpty()
                                ? null
                                : evidenceView(verificationTrace.evidence().getFirst());
                        return new PaymentTraceLine(
                                line.id(), line.claimedValue(), line.certifiedValue(), line.certificationReason(),
                                toView(valuation), toView(item), primaryEvidence,
                                verificationTrace.verificationPackage().id(), measurementId,
                                "ACCEPTED_MEASUREMENT_TYPED_TRACE_COMPLETE", verificationTrace);
                    }
                    var evidence = repository.findRevisionEvidence(valuation.sourceDocumentRevisionId(), projectId);
                    EvidenceView evidenceView = evidence == null ? null : new EvidenceView(
                            evidence.revisionId(), evidence.documentId(), evidence.documentNumber(), evidence.title(),
                            evidence.revisionCode(), evidence.revisionStatus(), evidence.contentSha256(), evidence.scopeId());
                    return new PaymentTraceLine(
                            line.id(), line.claimedValue(), line.certifiedValue(), line.certificationReason(),
                            toView(valuation), toView(item), evidenceView,
                            null, null, "DIRECT_CONTROLLED_DOCUMENT_REVISION", null);
                }).toList();
        return new PaymentTrace(toView(payment), toView(contract), toView(app), lines);
    }

    private static EvidenceView evidenceView(VerificationService.EvidenceView evidence) {
        return new EvidenceView(
                evidence.documentRevisionId(), evidence.documentId(), evidence.documentNumber(), evidence.title(),
                evidence.revisionCode(), evidence.revisionStatus(), evidence.contentSha256(), evidence.scopeId());
    }

    private boolean canViewContract(UUID actorUserId, CommercialRepository.ContractRow contract) {
        try {
            financialAccessService.requireContractView(
                    actorUserId, contract.projectId(), contract.payerOrganizationId(), contract.payeeOrganizationId());
            return true;
        } catch (ResponseStatusException ex) {
            if (ex.getStatusCode().value() == 403) return false;
            throw ex;
        }
    }

    private CommercialRepository.ParticipantRow requireParticipant(UUID projectId, UUID participantId) {
        var participant = repository.findParticipant(projectId, participantId);
        if (participant == null || !participant.status().equals("ACTIVE")) {
            throw bad("Project participant is not active in this project: " + participantId);
        }
        return participant;
    }

    private CommercialRepository.ContractRow requireContract(UUID projectId, UUID contractId) {
        var contract = repository.findContract(contractId)
                .orElseThrow(() -> notFound("Contract not found: " + contractId));
        if (!contract.projectId().equals(projectId)) throw notFound("Contract not found in project: " + contractId);
        return contract;
    }

    private CommercialRepository.ItemRow requireItem(UUID projectId, UUID contractId, UUID itemId) {
        var item = repository.findItem(itemId)
                .orElseThrow(() -> notFound("Contract item not found: " + itemId));
        if (!item.projectId().equals(projectId) || !item.contractId().equals(contractId)) {
            throw notFound("Contract item not found in requested project/contract");
        }
        return item;
    }

    private CommercialRepository.PaymentApplicationRow requireApplication(UUID projectId, UUID applicationId) {
        var app = repository.findApplication(applicationId)
                .orElseThrow(() -> notFound("Payment application not found: " + applicationId));
        if (!app.projectId().equals(projectId)) throw notFound("Payment application not found in project: " + applicationId);
        return app;
    }

    private void validateEvidence(UUID projectId, UUID revisionId) {
        if (revisionId != null && repository.findRevisionEvidence(revisionId, projectId) == null) {
            throw bad("sourceDocumentRevisionId must belong to the same project");
        }
    }

    private String projectCurrency(UUID projectId, String requested) {
        String currency = requested == null || requested.isBlank()
                ? projectService.get(projectId).currency()
                : requested.trim().toUpperCase(Locale.ROOT);
        if (!currency.equals(projectService.get(projectId).currency())) {
            throw bad("Commercial foundation currently requires project currency "
                    + projectService.get(projectId).currency() + "; FX conversion must be explicit");
        }
        return currency;
    }

    private static BigDecimal positive(BigDecimal value, String field) {
        if (value == null || value.signum() <= 0) throw bad(field + " must be greater than zero");
        return scale(value);
    }

    private static BigDecimal nonNegative(BigDecimal value, String field) {
        if (value == null || value.signum() < 0) throw bad(field + " cannot be negative");
        return scale(value);
    }

    private static BigDecimal optionalNonNegative(BigDecimal value, String field) {
        return value == null ? null : nonNegative(value, field);
    }

    private static BigDecimal nonNegativeDefault(BigDecimal value) {
        return value == null ? BigDecimal.ZERO.setScale(4) : nonNegative(value, "amount");
    }

    private static BigDecimal scale(BigDecimal value) {
        return value.setScale(4, RoundingMode.HALF_UP);
    }

    private static String code(String value, String field) {
        return text(value, field).toUpperCase(Locale.ROOT).replace(' ', '_');
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) throw bad(field + " is required");
        return value.trim();
    }

    private static String optional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
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

    private static ContractView toView(CommercialRepository.ContractRow row) {
        return new ContractView(row.id(), row.projectId(), row.payerParticipantId(), row.payeeParticipantId(),
                row.payerOrganizationId(), row.payeeOrganizationId(), row.contractNumber(), row.contractType(),
                row.currency(), row.originalValue(), row.visibilityPolicy(), row.status(), row.version());
    }

    private static ContractItemView toView(CommercialRepository.ItemRow row) {
        return new ContractItemView(row.id(), row.contractId(), row.scopeId(), row.itemCode(), row.description(),
                row.valuationMethod(), row.unit(), row.plannedQuantity(), row.rate(), row.contractValue(),
                row.dueDate(), row.status(), row.version());
    }

    private ValuationView toView(CommercialRepository.ValuationRow row) {
        return new ValuationView(row.id(), row.projectId(), row.contractId(), row.scopeId(), row.contractItemId(),
                row.valuationNumber(), row.sourceType(), row.sourceReference(), row.sourceDocumentRevisionId(),
                quantityValuationRepository.measurementId(row.id()), row.unit(), row.acceptedQuantity(), row.rate(),
                row.grossValue(), row.priorValue(), row.currentValue(), row.cumulativeValue(), row.retention(),
                row.otherDeductions(), row.eligibleValue(), row.status(), row.version());
    }

    private static PaymentApplicationView toView(CommercialRepository.PaymentApplicationRow row) {
        return new PaymentApplicationView(row.id(), row.projectId(), row.contractId(), row.applicationNumber(),
                row.periodFrom(), row.periodTo(), row.dueDate(), row.claimedAmount(), row.certifiedAmount(), row.status(),
                row.submittedBy(), row.certifiedBy(), row.submittedAt(), row.certifiedAt(),
                row.sourceDocumentRevisionId(), row.version());
    }

    private static PaymentApplicationLineView toView(CommercialRepository.ApplicationLineRow row) {
        return new PaymentApplicationLineView(row.id(), row.paymentApplicationId(), row.valuationLineId(),
                row.claimedValue(), row.certifiedValue(), row.certificationReason());
    }

    private static PaymentView toView(CommercialRepository.PaymentRow row) {
        return new PaymentView(row.id(), row.projectId(), row.contractId(), row.paymentApplicationId(),
                row.paymentReference(), row.amount(), row.currency(), row.paidAt(), row.payerOrganizationId(),
                row.payeeOrganizationId(), row.status(), row.sourceDocumentRevisionId(), row.version());
    }

    public record ContractView(UUID id, UUID projectId, UUID payerParticipantId, UUID payeeParticipantId,
                               UUID payerOrganizationId, UUID payeeOrganizationId, String contractNumber,
                               String contractType, String currency, BigDecimal originalValue,
                               String visibilityPolicy, String status, long version) {}
    public record ContractItemView(UUID id, UUID contractId, UUID scopeId, String itemCode, String description,
                                   String valuationMethod, String unit, BigDecimal plannedQuantity, BigDecimal rate,
                                   BigDecimal contractValue, LocalDate dueDate, String status, long version) {}
    public record ValuationView(UUID id, UUID projectId, UUID contractId, UUID scopeId, UUID contractItemId,
                                String valuationNumber, String sourceType, String sourceReference,
                                UUID sourceDocumentRevisionId, UUID measurementId, String unit,
                                BigDecimal acceptedQuantity, BigDecimal rate, BigDecimal grossValue,
                                BigDecimal priorValue, BigDecimal currentValue, BigDecimal cumulativeValue,
                                BigDecimal retention, BigDecimal otherDeductions, BigDecimal eligibleValue,
                                String status, long version) {}
    public record PaymentApplicationView(UUID id, UUID projectId, UUID contractId, String applicationNumber,
                                         LocalDate periodFrom, LocalDate periodTo, LocalDate dueDate,
                                         BigDecimal claimedAmount, BigDecimal certifiedAmount, String status,
                                         UUID submittedBy, UUID certifiedBy, Instant submittedAt, Instant certifiedAt,
                                         UUID sourceDocumentRevisionId, long version) {}
    public record PaymentApplicationLineView(UUID id, UUID paymentApplicationId, UUID valuationLineId,
                                             BigDecimal claimedValue, BigDecimal certifiedValue,
                                             String certificationReason) {}
    public record CertificationLine(UUID valuationLineId, BigDecimal certifiedValue, String reason) {}
    public record PaymentView(UUID id, UUID projectId, UUID contractId, UUID paymentApplicationId,
                              String paymentReference, BigDecimal amount, String currency, Instant paidAt,
                              UUID payerOrganizationId, UUID payeeOrganizationId, String status,
                              UUID sourceDocumentRevisionId, long version) {}
    public record ContractSummary(UUID contractId, BigDecimal originalValue, BigDecimal approvedChanges,
                                  BigDecimal currentValue, BigDecimal valuedToDate, BigDecimal claimedToDate,
                                  BigDecimal certifiedToDate, BigDecimal paidToDate, BigDecimal retentionToDate,
                                  BigDecimal outstandingCertified) {}
    public record EvidenceView(UUID revisionId, UUID documentId, String documentNumber, String title,
                               String revisionCode, String revisionStatus, String contentSha256, UUID scopeId) {}
    public record PaymentTraceLine(UUID applicationLineId, BigDecimal claimedValue, BigDecimal certifiedValue,
                                   String certificationReason, ValuationView valuation, ContractItemView contractItem,
                                   EvidenceView controlledEvidence, UUID verificationPackageId, UUID measurementId,
                                   String verificationMappingStatus, VerificationService.TraceView verificationTrace) {}
    public record PaymentTrace(PaymentView payment, ContractView contract,
                               PaymentApplicationView paymentApplication, List<PaymentTraceLine> lines) {}
}
