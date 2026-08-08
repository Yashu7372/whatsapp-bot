package com.whatsappbot.document;

import com.whatsappbot.features.FeatureAccessService;
import com.whatsappbot.features.FeatureCode;
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
    private final FeatureAccessService featureAccessService;

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

    /** Resolves the tenant and asserts the document-control entitlement in one step, so no
     *  endpoint on this controller can be reached by a tenant without the feature. */
    private UUID tenantId(Claims claims){
        UUID tenantId=UUID.fromString((String)claims.get("tenantId"));
        featureAccessService.assertAccess(tenantId,FeatureCode.DOCUMENT_CONTROL);
        return tenantId;
    }
    private static UUID userId(Claims claims){return UUID.fromString(claims.getSubject());}
}
