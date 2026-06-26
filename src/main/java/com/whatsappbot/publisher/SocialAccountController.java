package com.whatsappbot.publisher;

import com.whatsappbot.domain.tenant.TenantRepository;
import com.whatsappbot.features.FeatureAccessService;
import com.whatsappbot.features.FeatureCode;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/social-accounts")
@RequiredArgsConstructor
public class SocialAccountController {

    private final SocialAccountRepository socialAccountRepository;
    private final TenantRepository tenantRepository;
    private final FeatureAccessService featureAccessService;

    @GetMapping
    public ResponseEntity<List<SocialAccountResponse>> list(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        featureAccessService.assertAccess(tenantId, FeatureCode.SCHEDULED_PUBLISHING);
        return ResponseEntity.ok(
                socialAccountRepository.findAllByTenantId(tenantId)
                        .stream().map(this::toResponse).toList());
    }

    @PostMapping
    public ResponseEntity<SocialAccountResponse> create(
            @AuthenticationPrincipal Claims claims,
            @RequestBody CreateSocialAccountRequest req) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        featureAccessService.assertAccess(tenantId, FeatureCode.SCHEDULED_PUBLISHING);

        SocialAccountEntity account = new SocialAccountEntity();
        account.setTenant(tenantRepository.getReferenceById(tenantId));
        account.setPlatform(req.platform());
        account.setAccountName(req.accountName());
        account.setExternalAccountId(req.externalAccountId());
        account.setMetadata(req.metadata() != null ? req.metadata() : java.util.Map.of());
        return ResponseEntity.ok(toResponse(socialAccountRepository.save(account)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @AuthenticationPrincipal Claims claims,
            @PathVariable UUID id) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        featureAccessService.assertAccess(tenantId, FeatureCode.SCHEDULED_PUBLISHING);
        SocialAccountEntity account = socialAccountRepository.findByIdAndTenantId(id, tenantId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Social account not found"));
        socialAccountRepository.delete(account);
        return ResponseEntity.noContent().build();
    }

    private SocialAccountResponse toResponse(SocialAccountEntity a) {
        return new SocialAccountResponse(a.getId(), a.getPlatform(), a.getAccountName(),
                a.getExternalAccountId(), a.getStatus(), a.getCreatedAt());
    }

    public record CreateSocialAccountRequest(String platform, String accountName,
                                              String externalAccountId, java.util.Map<String, Object> metadata) {}

    public record SocialAccountResponse(UUID id, String platform, String accountName,
                                         String externalAccountId, String status, LocalDateTime createdAt) {}
}
