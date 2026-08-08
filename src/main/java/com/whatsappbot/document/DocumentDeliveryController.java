package com.whatsappbot.document;

import com.whatsappbot.features.FeatureAccessService;
import com.whatsappbot.features.FeatureCode;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class DocumentDeliveryController {
    private final DocumentDeliveryService service;
    private final FeatureAccessService featureAccessService;

    @PostMapping("/documents/{documentId}/issue")
    public ResponseEntity<DocumentDeliveryService.IssuedRevision> issueRevision(
            @AuthenticationPrincipal Claims claims,@PathVariable UUID documentId,@RequestBody IssueRequest request){
        return ResponseEntity.ok(service.issueCurrentRevision(tenantId(claims),userId(claims),documentId,request.purpose()));
    }

    @GetMapping("/projects/{projectId}/issued-revisions")
    public ResponseEntity<List<DocumentDeliveryRepository.IssuedRevisionView>> issuedRevisions(
            @AuthenticationPrincipal Claims claims,@PathVariable UUID projectId){
        return ResponseEntity.ok(service.issuedRevisions(tenantId(claims),userId(claims),projectId));
    }

    @GetMapping("/projects/{projectId}/transmittals")
    public ResponseEntity<List<DocumentDeliveryRepository.TransmittalView>> list(
            @AuthenticationPrincipal Claims claims,@PathVariable UUID projectId){
        return ResponseEntity.ok(service.list(tenantId(claims),userId(claims),projectId));
    }

    @PostMapping("/projects/{projectId}/transmittals")
    public ResponseEntity<Map<String,UUID>> create(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId,
                                                    @RequestBody DocumentDeliveryService.CreateTransmittalRequest request){
        return ResponseEntity.ok(Map.of("id",service.createTransmittal(tenantId(claims),userId(claims),projectId,request)));
    }

    @PostMapping("/transmittals/{transmittalId}/items")
    public ResponseEntity<Void> addItem(@AuthenticationPrincipal Claims claims,@PathVariable UUID transmittalId,@RequestBody AddItemRequest request){
        service.addItem(tenantId(claims),userId(claims),transmittalId,request.documentId(),request.versionId());return ResponseEntity.noContent().build();
    }

    @PostMapping("/transmittals/{transmittalId}/recipients/{organizationId}")
    public ResponseEntity<Void> addRecipient(@AuthenticationPrincipal Claims claims,@PathVariable UUID transmittalId,@PathVariable UUID organizationId){
        service.addRecipient(tenantId(claims),userId(claims),transmittalId,organizationId);return ResponseEntity.noContent().build();
    }

    @PostMapping("/transmittals/{transmittalId}/issue")
    public ResponseEntity<Void> issue(@AuthenticationPrincipal Claims claims,@PathVariable UUID transmittalId){service.issueTransmittal(tenantId(claims),userId(claims),transmittalId);return ResponseEntity.noContent().build();}

    @PostMapping("/transmittals/{transmittalId}/acknowledge")
    public ResponseEntity<Void> acknowledge(@AuthenticationPrincipal Claims claims,@PathVariable UUID transmittalId){service.acknowledge(tenantId(claims),userId(claims),transmittalId);return ResponseEntity.noContent().build();}

    public record IssueRequest(String purpose){}
    public record AddItemRequest(UUID documentId,UUID versionId){}
    /** Resolves the tenant and asserts the document-control entitlement in one step, so no
     *  endpoint on this controller can be reached by a tenant without the feature. */
    private UUID tenantId(Claims claims){
        UUID tenantId=UUID.fromString((String)claims.get("tenantId"));
        featureAccessService.assertAccess(tenantId,FeatureCode.DOCUMENT_CONTROL);
        return tenantId;
    }
    private static UUID userId(Claims claims){return UUID.fromString(claims.getSubject());}
}
