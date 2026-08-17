package com.whatsappbot.forecast;

import com.whatsappbot.auth.TenantUserEntity;
import com.whatsappbot.auth.UserRole;
import com.whatsappbot.project.PartyRole;
import com.whatsappbot.project.ProjectAccessService;
import com.whatsappbot.project.ProjectService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ForecastIntelligenceService {
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final ForecastIntelligenceRepository repository;
    private final ProjectService projectService;
    private final ProjectAccessService accessService;

    @Transactional
    public Dashboard refresh(UUID tenantId, UUID userId, UUID projectId) {
        TenantUserEntity actor = requireViewer(tenantId, userId, projectId);
        requireWholeProject(actor, tenantId, projectId);

        ForecastIntelligenceRepository.Totals totals = repository.loadTotals(tenantId, projectId);
        BigDecimal pending = repository.pendingVariationExposure(tenantId, projectId);
        ForecastIntelligenceRepository.Progress progress = repository.latestProgress(tenantId, projectId);

        BigDecimal baseEac = totals.actual().add(totals.etc()).max(totals.committed());
        BigDecimal exposureEac = baseEac.add(pending);
        BigDecimal variance = totals.budget().subtract(exposureEac);
        BigDecimal costPct = percent(totals.actual(), totals.budget());

        UUID snapshotId = repository.upsertSnapshot(tenantId, projectId, userId, LocalDate.now(), totals, pending,
                baseEac, exposureEac, variance, progress, costPct);
        repository.replaceWarnings(tenantId, projectId, snapshotId,
                buildWarnings(totals.budget(), exposureEac, pending, costPct, progress));
        refreshConsultantKpis(tenantId, projectId, exposureEac, totals.budget());
        return dashboard(tenantId, userId, projectId);
    }

    @Transactional(readOnly = true)
    public Dashboard dashboard(UUID tenantId, UUID userId, UUID projectId) {
        TenantUserEntity actor = requireViewer(tenantId, userId, projectId);
        requireWholeProject(actor, tenantId, projectId);
        List<ForecastSnapshot> history = repository.history(tenantId, projectId, 12);
        ForecastSnapshot latest = history.isEmpty() ? null : history.get(0);
        List<WarningView> warnings = latest == null ? List.of() : repository.warnings(latest.id());
        return new Dashboard(latest, warnings, repository.latestKpis(tenantId, projectId), history);
    }

    private List<ForecastIntelligenceRepository.WarningSeed> buildWarnings(
            BigDecimal budget, BigDecimal exposureEac, BigDecimal pending, BigDecimal costPct,
            ForecastIntelligenceRepository.Progress progress) {
        List<ForecastIntelligenceRepository.WarningSeed> warnings = new ArrayList<>();

        BigDecimal overrun = exposureEac.compareTo(budget) > 0
                ? percent(exposureEac.subtract(budget), budget) : BigDecimal.ZERO;
        if (overrun.signum() > 0)
            warnings.add(seed("FORECAST_OVERRUN", overrun, BigDecimal.valueOf(5), "Forecast final cost exceeds current budget"));

        BigDecimal variationPct = percent(pending, budget);
        if (variationPct.compareTo(BigDecimal.valueOf(5)) >= 0)
            warnings.add(seed("VARIATION_EXPOSURE", variationPct, BigDecimal.valueOf(5), "Open variation exposure is material"));

        if (progress.physical() != null) {
            BigDecimal gap = costPct.subtract(progress.physical());
            if (gap.compareTo(BigDecimal.TEN) >= 0)
                warnings.add(seed("COST_AHEAD_OF_PROGRESS", gap, BigDecimal.TEN, "Cost consumption is ahead of physical progress"));
        }

        if (progress.physical() != null && progress.schedule() != null) {
            BigDecimal gap = progress.schedule().subtract(progress.physical());
            if (gap.compareTo(BigDecimal.TEN) >= 0)
                warnings.add(seed("PROGRESS_BEHIND_PLAN", gap, BigDecimal.TEN, "Physical progress is behind programme progress"));
        }
        return warnings;
    }

    private void refreshConsultantKpis(UUID tenantId, UUID projectId, BigDecimal controlForecast, BigDecimal budget) {
        for (UUID orgId : repository.consultantOrganizations(tenantId, projectId)) {
            int due = repository.dueDocuments(tenantId, projectId, orgId);
            int overdue = repository.overdueDocuments(tenantId, projectId, orgId);
            BigDecimal sla = due == 0 ? HUNDRED
                    : HUNDRED.subtract(BigDecimal.valueOf(overdue).multiply(HUNDRED)
                    .divide(BigDecimal.valueOf(due), 2, RoundingMode.HALF_UP)).max(BigDecimal.ZERO);

            BigDecimal partyForecast = repository.latestPartyForecast(tenantId, projectId, orgId);
            BigDecimal gap = partyForecast == null ? null : partyForecast.subtract(controlForecast).abs();
            BigDecimal alignment = partyForecast == null
                    ? BigDecimal.ZERO
                    : HUNDRED.subtract(percent(gap, budget)).max(BigDecimal.ZERO);
            BigDecimal overall = sla.multiply(BigDecimal.valueOf(.6))
                    .add(alignment.multiply(BigDecimal.valueOf(.4)))
                    .setScale(2, RoundingMode.HALF_UP);

            repository.upsertConsultantKpi(tenantId, projectId, orgId, LocalDate.now(), sla, alignment, overall,
                    overdue, due, partyForecast, controlForecast, gap);
        }
    }

    private TenantUserEntity requireViewer(UUID tenantId, UUID userId, UUID projectId) {
        projectService.get(tenantId, userId, projectId);
        TenantUserEntity actor = accessService.requireActiveUser(tenantId, userId);
        if (accessService.isTenantAdministrator(actor)) return actor;
        if (actor.getRole() != UserRole.MANAGER && actor.getRole() != UserRole.ADMIN)
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project forecasting requires commercial manager or administrator access");
        return actor;
    }

    private void requireWholeProject(TenantUserEntity actor, UUID tenantId, UUID projectId) {
        if (accessService.isTenantAdministrator(actor)) return;
        List<PartyRole> roles = accessService.rolesOnProject(tenantId, projectId, actor);
        if (!roles.contains(PartyRole.CLIENT) && !roles.contains(PartyRole.CONSULTANT))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Project-wide forecasting is restricted to client/consultant commercial roles");
    }

    private static BigDecimal percent(BigDecimal value, BigDecimal base) {
        return base == null || base.signum() == 0 ? BigDecimal.ZERO
                : value.multiply(HUNDRED).divide(base, 2, RoundingMode.HALF_UP);
    }

    private static ForecastIntelligenceRepository.WarningSeed seed(String code, BigDecimal metric, BigDecimal threshold, String title) {
        String severity = metric.compareTo(threshold.multiply(BigDecimal.valueOf(2))) >= 0 ? "CRITICAL" : "ATTENTION";
        return new ForecastIntelligenceRepository.WarningSeed(code, severity, title, metric, threshold);
    }

    public record Dashboard(ForecastSnapshot latest,List<WarningView>warnings,List<KpiView>consultantKpis,List<ForecastSnapshot>history){}
    public record ForecastSnapshot(UUID id,LocalDate snapshotDate,BigDecimal currentBudget,BigDecimal actualCost,BigDecimal committedCost,BigDecimal estimateToComplete,BigDecimal pendingVariationExposure,BigDecimal baseEac,BigDecimal exposureEac,BigDecimal forecastVariance,BigDecimal physicalProgressPercent,BigDecimal scheduleProgressPercent,BigDecimal costConsumptionPercent){}
    public record WarningView(String code,String severity,String title,BigDecimal metricValue,BigDecimal thresholdValue){}
    public record KpiView(UUID organizationId,String organizationName,LocalDate snapshotDate,BigDecimal documentSlaHealth,BigDecimal forecastAlignment,BigDecimal overallControlHealth,int overdueDocuments,int dueDocuments,BigDecimal latestPartyForecast,BigDecimal controlForecast,BigDecimal forecastGap){}
}
