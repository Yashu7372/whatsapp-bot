package com.whatsappbot.commercial;

import com.whatsappbot.document.DocumentEntity;
import com.whatsappbot.document.DocumentRepository;
import com.whatsappbot.document.DocumentStatus;
import com.whatsappbot.payment.PaymentApplicationEntity;
import com.whatsappbot.payment.PaymentApplicationRepository;
import com.whatsappbot.payment.PaymentApplicationStatus;
import com.whatsappbot.project.ProjectEntity;
import com.whatsappbot.project.ProjectService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class CommercialIntelligenceService {

    private static final BigDecimal HUNDRED = new BigDecimal("100");

    private final ProjectService projectService;
    private final PaymentApplicationRepository paymentRepository;
    private final DocumentRepository documentRepository;
    private final CommercialInsightAgent insightAgent;

    @Transactional(readOnly = true)
    public CommercialOverview overview(UUID tenantId, UUID userId, UUID projectId, boolean includeAi) {
        // ProjectService performs tenant + project visibility authorization for the caller.
        ProjectEntity project = projectService.get(tenantId, userId, projectId);
        List<PaymentApplicationEntity> applications =
                paymentRepository.findAllByTenantIdAndProjectIdOrderByCreatedAtDesc(tenantId, projectId);
        List<DocumentEntity> documents = documentRepository.findAllByTenantIdOrderByUpdatedAtDesc(tenantId)
                .stream().filter(d -> projectId.equals(d.getProjectId())).toList();

        BigDecimal submitted = sum(applications, PaymentApplicationStatus.SUBMITTED);
        BigDecimal certified = sum(applications, PaymentApplicationStatus.CERTIFIED)
                .add(sum(applications, PaymentApplicationStatus.PAID));
        BigDecimal paid = sum(applications, PaymentApplicationStatus.PAID);
        BigDecimal retentionHeld = applications.stream()
                .filter(a -> a.getStatus() == PaymentApplicationStatus.CERTIFIED
                        || a.getStatus() == PaymentApplicationStatus.PAID)
                .map(PaymentApplicationEntity::getRetentionAmount)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal approvedWork = documents.stream()
                .filter(d -> d.getStatus() == DocumentStatus.APPROVED)
                .map(DocumentEntity::getApprovedValue)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal contractValue = nz(project.getContractValue());
        BigDecimal remainingBudget = contractValue.subtract(certified).max(BigDecimal.ZERO);
        BigDecimal certifiedPct = percent(certified, contractValue);
        BigDecimal paidPct = percent(paid, contractValue);

        LocalDateTime now = LocalDateTime.now();
        long overdueDocuments = documents.stream()
                .filter(d -> d.getDueAt() != null && d.getDueAt().isBefore(now))
                .filter(d -> d.getStatus() != DocumentStatus.APPROVED)
                .count();
        long dueNext7Days = documents.stream()
                .filter(d -> d.getDueAt() != null && !d.getDueAt().isBefore(now)
                        && !d.getDueAt().isAfter(now.plusDays(7)))
                .filter(d -> d.getStatus() != DocumentStatus.APPROVED)
                .count();

        BigDecimal unclaimedApprovedWork = approvedWork.subtract(
                applications.stream()
                        .filter(a -> a.getStatus() != PaymentApplicationStatus.REJECTED)
                        .map(PaymentApplicationEntity::getGrossClaimed)
                        .filter(v -> v != null)
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .max(BigDecimal.ZERO);

        Forecast forecast = forecast(contractValue, certified, paid, project.getEndDate(), overdueDocuments);
        List<String> rules = deterministicSuggestions(contractValue, certified, paid, submitted,
                unclaimedApprovedWork, overdueDocuments, dueNext7Days, forecast);

        String aiNarrative = null;
        if (includeAi) {
            try {
                aiNarrative = insightAgent.analyse(buildGroundedPrompt(project, contractValue, submitted,
                        certified, paid, retentionHeld, approvedWork, unclaimedApprovedWork,
                        overdueDocuments, dueNext7Days, forecast));
            } catch (RuntimeException ex) {
                log.warn("Commercial AI insight unavailable. project={}", projectId, ex);
            }
        }

        return new CommercialOverview(project.getId(), project.getProjectCode(), project.getName(),
                project.getCurrency(), contractValue, submitted, certified, paid, retentionHeld,
                remainingBudget, approvedWork, unclaimedApprovedWork, certifiedPct, paidPct,
                applications.size(), documents.size(), overdueDocuments, dueNext7Days,
                forecast, rules, aiNarrative);
    }

    private static BigDecimal sum(List<PaymentApplicationEntity> apps, PaymentApplicationStatus status) {
        return apps.stream().filter(a -> a.getStatus() == status)
                .map(PaymentApplicationEntity::getNetCertified).filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal percent(BigDecimal part, BigDecimal total) {
        if (total == null || total.signum() <= 0) return BigDecimal.ZERO;
        return part.multiply(HUNDRED).divide(total, 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal value) { return value == null ? BigDecimal.ZERO : value; }

    private static Forecast forecast(BigDecimal contract, BigDecimal certified, BigDecimal paid,
                                     java.time.LocalDate endDate, long overdue) {
        BigDecimal exposure = certified.subtract(paid).max(BigDecimal.ZERO);
        String risk = overdue > 5 ? "HIGH" : overdue > 0 || exposure.signum() > 0 ? "MEDIUM" : "LOW";
        BigDecimal forecastFinalCost = certified.max(paid);
        if (contract.signum() > 0 && forecastFinalCost.compareTo(contract) > 0) risk = "HIGH";
        return new Forecast(forecastFinalCost, exposure, endDate, risk);
    }

    private static List<String> deterministicSuggestions(BigDecimal contract, BigDecimal certified,
            BigDecimal paid, BigDecimal submitted, BigDecimal unclaimed, long overdue, long next7,
            Forecast forecast) {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        if (overdue > 0) result.add(overdue + " document SLA item(s) are overdue; prioritise review before the next IPC cut-off.");
        if (next7 > 0) result.add(next7 + " document(s) become due within 7 days; confirm owners and evidence readiness.");
        if (unclaimed.signum() > 0) result.add("Approved work worth " + unclaimed.toPlainString() + " is not yet represented in a live IPC claim.");
        if (certified.compareTo(paid) > 0) result.add("Certified but unpaid exposure is " + certified.subtract(paid).toPlainString() + ".");
        if (submitted.signum() > 0) result.add("Submitted IPC value awaiting certification is " + submitted.toPlainString() + ".");
        if (contract.signum() > 0 && percent(certified, contract).compareTo(new BigDecimal("90")) > 0)
            result.add("Certified value exceeds 90% of contract value; review remaining scope, variations and retention before further certification.");
        if (result.isEmpty()) result.add("No immediate commercial or document-SLA exception is visible from current project records.");
        return result.stream().limit(4).toList();
    }

    private static String buildGroundedPrompt(ProjectEntity p, BigDecimal contract, BigDecimal submitted,
            BigDecimal certified, BigDecimal paid, BigDecimal retention, BigDecimal approvedWork,
            BigDecimal unclaimed, long overdue, long next7, Forecast f) {
        return "Project=" + p.getProjectCode() + " / " + p.getName()
                + "\nCurrency=" + p.getCurrency()
                + "\nContractValue=" + contract
                + "\nSubmittedIPC=" + submitted
                + "\nCertifiedIPC=" + certified
                + "\nPaid=" + paid
                + "\nRetentionHeld=" + retention
                + "\nApprovedWorkEvidence=" + approvedWork
                + "\nApprovedButUnclaimed=" + unclaimed
                + "\nOverdueDocumentSLA=" + overdue
                + "\nDocumentsDueNext7Days=" + next7
                + "\nCertifiedUnpaidExposure=" + f.certifiedUnpaidExposure()
                + "\nContractEndDate=" + f.contractEndDate()
                + "\nCalculatedRisk=" + f.risk();
    }

    public record Forecast(BigDecimal forecastFinalCost, BigDecimal certifiedUnpaidExposure,
                           java.time.LocalDate contractEndDate, String risk) {}

    public record CommercialOverview(UUID projectId, String projectCode, String projectName,
            String currency, BigDecimal contractValue, BigDecimal submittedIpc, BigDecimal certifiedIpc,
            BigDecimal paidToDate, BigDecimal retentionHeld, BigDecimal remainingBudget,
            BigDecimal approvedWorkEvidence, BigDecimal approvedButUnclaimed,
            BigDecimal certifiedPercent, BigDecimal paidPercent, int ipcCount, int documentCount,
            long overdueDocumentSla, long dueNext7Days, Forecast forecast,
            List<String> suggestions, String aiNarrative) {}
}
