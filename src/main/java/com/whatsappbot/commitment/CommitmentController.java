package com.whatsappbot.commitment;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/commercial-facts")
@RequiredArgsConstructor
public class CommitmentController {
    private final CommitmentService service;

    @GetMapping("/summary")
    public ResponseEntity<CommitmentService.CommercialFactSummary> summary(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId){
        return ResponseEntity.ok(service.summary(tenantId(claims),userId(claims),projectId));
    }

    @GetMapping("/commitments")
    public ResponseEntity<List<CommitmentService.CommitmentView>> commitments(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId){
        return ResponseEntity.ok(service.commitments(tenantId(claims),userId(claims),projectId));
    }

    @PostMapping("/commitments")
    public ResponseEntity<Map<String,UUID>> createCommitment(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId,@RequestBody CommitmentService.CreateCommitmentRequest request){
        return ResponseEntity.ok(Map.of("id",service.createCommitment(tenantId(claims),userId(claims),projectId,request)));
    }

    @GetMapping("/materials")
    public ResponseEntity<List<CommitmentService.MaterialReceiptView>> materials(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId){
        return ResponseEntity.ok(service.materials(tenantId(claims),userId(claims),projectId));
    }

    @PostMapping("/materials")
    public ResponseEntity<Map<String,UUID>> recordMaterial(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId,@RequestBody CommitmentService.CreateMaterialReceiptRequest request){
        return ResponseEntity.ok(Map.of("id",service.recordMaterial(tenantId(claims),userId(claims),projectId,request)));
    }

    @GetMapping("/variations")
    public ResponseEntity<List<CommitmentService.VariationView>> variations(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId){
        return ResponseEntity.ok(service.variations(tenantId(claims),userId(claims),projectId));
    }

    @PostMapping("/variations")
    public ResponseEntity<Map<String,UUID>> createVariation(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId,@RequestBody CommitmentService.CreateVariationRequest request){
        return ResponseEntity.ok(Map.of("id",service.createVariation(tenantId(claims),userId(claims),projectId,request)));
    }

    @PostMapping("/variations/{variationId}/approve")
    public ResponseEntity<Void> approveVariation(@AuthenticationPrincipal Claims claims,@PathVariable UUID projectId,@PathVariable UUID variationId,@RequestBody ApproveVariationRequest request){
        service.approveVariation(tenantId(claims),userId(claims),projectId,variationId,request.approvedAmount());
        return ResponseEntity.noContent().build();
    }

    public record ApproveVariationRequest(BigDecimal approvedAmount){}
    private static UUID tenantId(Claims claims){return UUID.fromString((String)claims.get("tenantId"));}
    private static UUID userId(Claims claims){return UUID.fromString(claims.getSubject());}
}
