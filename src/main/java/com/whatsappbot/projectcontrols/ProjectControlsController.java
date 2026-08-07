package com.whatsappbot.projectcontrols;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/controls")
@RequiredArgsConstructor
public class ProjectControlsController {
    private final ProjectControlsService service;

    @GetMapping("/summary")
    public ResponseEntity<ProjectControlsService.ControlsSummary> summary(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId){
        return ResponseEntity.ok(service.summary(tenantId(claims),userId(claims),projectId));
    }

    @GetMapping("/contracts")
    public ResponseEntity<List<ProjectControlsService.ContractView>> contracts(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId){
        return ResponseEntity.ok(service.contracts(tenantId(claims),userId(claims),projectId));
    }

    @PostMapping("/contracts")
    public ResponseEntity<Map<String,UUID>> createContract(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId,
                                                            @RequestBody ProjectControlsService.CreateContractRequest request){
        return ResponseEntity.ok(Map.of("id",service.createContract(tenantId(claims),userId(claims),projectId,request)));
    }

    @GetMapping("/budget")
    public ResponseEntity<ProjectControlsService.BudgetView> budget(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId){
        ProjectControlsService.BudgetView budget=service.currentBudget(tenantId(claims),userId(claims),projectId);
        return budget==null?ResponseEntity.noContent().build():ResponseEntity.ok(budget);
    }

    @PostMapping("/budget/versions")
    public ResponseEntity<Map<String,UUID>> createBudgetVersion(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId,
                                                                 @RequestBody ProjectControlsService.CreateBudgetVersionRequest request){
        return ResponseEntity.ok(Map.of("id",service.createBudgetVersion(tenantId(claims),userId(claims),projectId,request)));
    }

    @PostMapping("/budget/versions/{versionId}/lines")
    public ResponseEntity<Map<String,UUID>> addBudgetLine(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId,@PathVariable UUID versionId,
                                                           @RequestBody ProjectControlsService.CreateBudgetLineRequest request){
        return ResponseEntity.ok(Map.of("id",service.addBudgetLine(tenantId(claims),userId(claims),projectId,versionId,request)));
    }

    @GetMapping("/forecasts")
    public ResponseEntity<List<ProjectControlsService.ForecastView>> forecasts(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId){
        return ResponseEntity.ok(service.forecasts(tenantId(claims),userId(claims),projectId));
    }

    @PostMapping("/forecasts")
    public ResponseEntity<Map<String,UUID>> createForecast(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId,
                                                            @RequestBody ProjectControlsService.CreateForecastRequest request){
        return ResponseEntity.ok(Map.of("id",service.createForecast(tenantId(claims),userId(claims),projectId,request)));
    }

    private static UUID tenantId(Claims claims){return UUID.fromString((String)claims.get("tenantId"));}
    private static UUID userId(Claims claims){return UUID.fromString(claims.getSubject());}
}
