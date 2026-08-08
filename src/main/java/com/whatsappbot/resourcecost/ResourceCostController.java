package com.whatsappbot.resourcecost;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/resource-costs")
@RequiredArgsConstructor
public class ResourceCostController {
    private final ResourceCostService service;

    @GetMapping("/summary")
    public ResponseEntity<ResourceCostService.ResourceCostSummary> summary(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId){return ResponseEntity.ok(service.summary(tenantId(claims),userId(claims),projectId));}
    @GetMapping("/resources")
    public ResponseEntity<List<ResourceCostService.ResourceView>> resources(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId){return ResponseEntity.ok(service.resources(tenantId(claims),userId(claims),projectId));}
    @PostMapping("/resources")
    public ResponseEntity<Map<String,UUID>> createResource(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId,@RequestBody ResourceCostService.CreateResourceRequest request){return ResponseEntity.ok(Map.of("id",service.createResource(tenantId(claims),userId(claims),projectId,request)));}
    @PostMapping("/resources/{resourceId}/rates")
    public ResponseEntity<Map<String,UUID>> addRate(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId,@PathVariable UUID resourceId,@RequestBody ResourceCostService.CreateRateRequest request){return ResponseEntity.ok(Map.of("id",service.addRate(tenantId(claims),userId(claims),projectId,resourceId,request)));}
    @PostMapping("/timesheets")
    public ResponseEntity<Map<String,UUID>> submitTimesheet(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId,@RequestBody ResourceCostService.CreateTimesheetRequest request){return ResponseEntity.ok(Map.of("id",service.submitTimesheet(tenantId(claims),userId(claims),projectId,request)));}
    @PostMapping("/timesheets/{timesheetId}/approve")
    public ResponseEntity<Void> approveTimesheet(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId,@PathVariable UUID timesheetId,@RequestParam(required=false) UUID budgetLineId){service.approveTimesheet(tenantId(claims),userId(claims),projectId,timesheetId,budgetLineId);return ResponseEntity.noContent().build();}
    @PostMapping("/equipment-usage")
    public ResponseEntity<Map<String,UUID>> recordUsage(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId,@RequestBody ResourceCostService.CreateUsageRequest request){return ResponseEntity.ok(Map.of("id",service.recordEquipmentUsage(tenantId(claims),userId(claims),projectId,request)));}
    @GetMapping("/actual-costs")
    public ResponseEntity<List<ResourceCostService.ActualCostView>> actualCosts(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId){return ResponseEntity.ok(service.actualCosts(tenantId(claims),userId(claims),projectId));}

    private static UUID tenantId(Claims claims){return UUID.fromString((String)claims.get("tenantId"));}
    private static UUID userId(Claims claims){return UUID.fromString(claims.getSubject());}
}
