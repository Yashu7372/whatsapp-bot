package com.whatsappbot.document;

import com.whatsappbot.project.ProjectAccessService;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/document-notifications")
@RequiredArgsConstructor
public class WorkflowNotificationController {
    private final WorkflowNotificationService service;
    private final ProjectAccessService access;

    @GetMapping
    public ResponseEntity<List<WorkflowNotificationRepository.InAppView>> mine(
            @AuthenticationPrincipal Claims claims,@RequestParam(defaultValue="100") int limit){
        UUID tenant=tenantId(claims),user=userId(claims);access.requireActiveUser(tenant,user);
        return ResponseEntity.ok(service.mine(tenant,user,limit));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Integer> unread(@AuthenticationPrincipal Claims claims){
        UUID tenant=tenantId(claims),user=userId(claims);access.requireActiveUser(tenant,user);
        return ResponseEntity.ok(service.unread(tenant,user));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> read(@AuthenticationPrincipal Claims claims,@PathVariable UUID id){
        UUID tenant=tenantId(claims),user=userId(claims);access.requireActiveUser(tenant,user);service.markRead(tenant,user,id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> readAll(@AuthenticationPrincipal Claims claims){
        UUID tenant=tenantId(claims),user=userId(claims);access.requireActiveUser(tenant,user);service.markAllRead(tenant,user);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/preferences")
    public ResponseEntity<WorkflowNotificationRepository.Preferences> preferences(@AuthenticationPrincipal Claims claims){
        UUID tenant=tenantId(claims),user=userId(claims);access.requireActiveUser(tenant,user);
        return ResponseEntity.ok(service.preferences(tenant,user));
    }

    @PutMapping("/preferences")
    public ResponseEntity<Void> preferences(@AuthenticationPrincipal Claims claims,@RequestBody PreferencesRequest req){
        UUID tenant=tenantId(claims),user=userId(claims);access.requireActiveUser(tenant,user);
        if(req.whatsappEnabled()&&(req.whatsappNumber()==null||req.whatsappNumber().isBlank()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"whatsappNumber is required when WhatsApp notifications are enabled");
        service.preferences(tenant,user,req.emailEnabled(),req.whatsappEnabled(),req.whatsappNumber());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/delivery-audit")
    public ResponseEntity<List<WorkflowNotificationRepository.DeliveryAudit>> audit(
            @AuthenticationPrincipal Claims claims,@RequestParam(defaultValue="200") int limit){
        UUID tenant=tenantId(claims),user=userId(claims);var actor=access.requireActiveUser(tenant,user);
        if(!access.isTenantAdministrator(actor)) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Only a true tenant administrator can view cross-user notification delivery audit");
        return ResponseEntity.ok(service.audit(tenant,limit));
    }

    private static UUID tenantId(Claims c){return UUID.fromString((String)c.get("tenantId"));}
    private static UUID userId(Claims c){return UUID.fromString(c.getSubject());}
    public record PreferencesRequest(boolean emailEnabled,boolean whatsappEnabled,String whatsappNumber){}
}
