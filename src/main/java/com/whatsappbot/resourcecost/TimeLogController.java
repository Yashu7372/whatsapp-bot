package com.whatsappbot.resourcecost;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "Log my time" — deliberately split from {@link ResourceCostController} so it sits under its own
 * feature gate (PROJECT_TIME_LOG, VIEW+MANAGE allowed for every role) rather than
 * PROJECT_RESOURCE_COST (MANAGER/ADMIN-only). Any active project participant may submit and see
 * their own organization's hours here; the computed rate/amount never appears in this response —
 * see {@link ResourceCostService.TimeLogView}. Only {@link ResourceCostController#approveTimesheet}
 * (which computes and records the cost) stays behind the MANAGER/ADMIN gate.
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/time-log")
@RequiredArgsConstructor
public class TimeLogController {
    private final ResourceCostService service;

    @PostMapping
    public ResponseEntity<Map<String,UUID>> submit(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId,@RequestBody ResourceCostService.CreateTimesheetRequest request){
        return ResponseEntity.ok(Map.of("id",service.submitTimesheet(tenantId(claims),userId(claims),projectId,request)));
    }

    @GetMapping
    public ResponseEntity<List<ResourceCostService.TimeLogView>> mine(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId,@RequestParam(required=false) UUID documentId){
        return ResponseEntity.ok(service.timeLog(tenantId(claims),userId(claims),projectId,documentId));
    }

    // Which resource ID is "me"/my crew — needed to submit a timesheet at all. No rate/cost field
    // exists on ResourceView, so this is safe to open up alongside the rest of this controller.
    @GetMapping("/resources")
    public ResponseEntity<List<ResourceCostService.ResourceView>> myResources(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId){
        return ResponseEntity.ok(service.myResources(tenantId(claims),userId(claims),projectId));
    }

    private static UUID tenantId(Claims claims){return UUID.fromString((String)claims.get("tenantId"));}
    private static UUID userId(Claims claims){return UUID.fromString(claims.getSubject());}
}
