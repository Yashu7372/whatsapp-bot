package com.yashu.projectcontrol.financialreporting;

import com.yashu.projectcontrol.access.ProjectControlPrincipal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}")
public class FinancialReadController {

    private final FinancialReadService service;

    public FinancialReadController(FinancialReadService service) {
        this.service = service;
    }

    @GetMapping("/financial-drilldown")
    public ResponseEntity<FinancialReadService.ProjectFinancialDrilldown> drilldown(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestParam UUID owningOrganizationId) {
        return ResponseEntity.ok(service.drilldown(principal.userId(), projectId, owningOrganizationId));
    }

    @GetMapping("/cash-flow")
    public ResponseEntity<FinancialReadService.CashFlowView> cashFlow(
            @PathVariable UUID projectId,
            @AuthenticationPrincipal ProjectControlPrincipal principal,
            @RequestParam UUID organizationId,
            @RequestParam LocalDate from,
            @RequestParam LocalDate to) {
        return ResponseEntity.ok(service.cashFlow(
                principal.userId(), projectId, organizationId, from, to));
    }
}
