package com.whatsappbot.project;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/projects/{projectId}/capabilities")
public class ProjectCapabilityAdminController {
    private final ProjectCapabilityAdminService service;
    private final PermissionAuditService audit;

    @GetMapping
    public ResponseEntity<List<ProjectCapabilityRepository.MatrixRow>> list(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId){
        return ResponseEntity.ok(service.list(tenantId(claims),userId(claims),projectId));
    }
    @PutMapping
    public ResponseEntity<Void> set(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId,
                                    @RequestBody ProjectCapabilityAdminService.ChangeRequest request){
        service.set(tenantId(claims),userId(claims),projectId,request);return ResponseEntity.noContent().build();
    }
    @DeleteMapping
    public ResponseEntity<Void> reset(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId,
                                      @RequestParam String partyRole,@RequestParam String userRole,@RequestParam String permissionCode){
        service.reset(tenantId(claims),userId(claims),projectId,partyRole,userRole,permissionCode);return ResponseEntity.noContent().build();
    }
    @GetMapping("/audit")
    public ResponseEntity<List<PermissionAuditService.AuditView>> audit(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId){
        service.list(tenantId(claims),userId(claims),projectId); // admin gate
        return ResponseEntity.ok(audit.list(tenantId(claims),projectId));
    }
    private static UUID tenantId(Claims c){return UUID.fromString((String)c.get("tenantId"));}
    private static UUID userId(Claims c){return UUID.fromString(c.getSubject());}
}
