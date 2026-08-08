package com.whatsappbot.document;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/documents/{documentId}")
public class DocumentSecurityController {
    private final DocumentSecurityService service;

    @PatchMapping("/security")
    public ResponseEntity<Void> updateSecurity(@AuthenticationPrincipal Claims claims,@PathVariable UUID documentId,
                                               @RequestBody DocumentSecurityService.UpdateSecurityRequest request){
        service.updateSecurity(tenantId(claims),userId(claims),documentId,request);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/grants")
    public ResponseEntity<Void> grant(@AuthenticationPrincipal Claims claims,@PathVariable UUID documentId,
                                      @RequestBody DocumentSecurityService.GrantRequest request){
        service.grant(tenantId(claims),userId(claims),documentId,request);
        return ResponseEntity.noContent().build();
    }

    private static UUID tenantId(Claims claims){return UUID.fromString((String)claims.get("tenantId"));}
    private static UUID userId(Claims claims){return UUID.fromString(claims.getSubject());}
}
