package com.whatsappbot.api;

import com.whatsappbot.platform.core.PlatformAccountEntity;
import com.whatsappbot.platform.core.PlatformAccountRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/platform-accounts")
@RequiredArgsConstructor
public class PlatformAccountController {

    private final PlatformAccountRepository platformAccountRepository;

    @GetMapping
    public ResponseEntity<List<PlatformAccountEntity>> list(@AuthenticationPrincipal Claims claims) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        return ResponseEntity.ok(platformAccountRepository.findAllByTenantIdAndActive(tenantId, true));
    }

    @PostMapping
    public ResponseEntity<ConnectResponse> connect(@AuthenticationPrincipal Claims claims,
                                                    @RequestBody ConnectRequest request) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        // Return a mock OAuth URL for local testing — real OAuth is wired in production via env config
        String mockOauthUrl = String.format(
                "https://www.%s.com/oauth/authorize?client_id=mock&redirect_uri=http://localhost:5173/platforms/callback&state=%s:%s",
                request.platformCode().toLowerCase(), tenantId, request.platformCode()
        );
        return ResponseEntity.ok(new ConnectResponse(mockOauthUrl));
    }

    @PostMapping("/callback")
    public ResponseEntity<PlatformAccountEntity> handleCallback(@AuthenticationPrincipal Claims claims,
                                                                 @RequestBody CallbackRequest request) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        PlatformAccountEntity account = new PlatformAccountEntity();
        account.setTenantId(tenantId);
        account.setPlatformCode(request.platformCode());
        account.setExternalAccountId(UUID.randomUUID().toString());
        account.setAccountName(request.accountName());
        account.setAccountHandle(request.accountHandle());
        account.setActive(true);
        account.setCreatedAt(LocalDateTime.now());
        account.setUpdatedAt(LocalDateTime.now());
        return ResponseEntity.ok(platformAccountRepository.save(account));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> disconnect(@AuthenticationPrincipal Claims claims,
                                           @PathVariable UUID id) {
        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));
        platformAccountRepository.findById(id).ifPresent(account -> {
            if (account.getTenantId().equals(tenantId)) {
                account.setActive(false);
                account.setUpdatedAt(LocalDateTime.now());
                platformAccountRepository.save(account);
            }
        });
        return ResponseEntity.noContent().build();
    }

    record ConnectRequest(String platformCode) {}
    record ConnectResponse(String oauthUrl) {}
    record CallbackRequest(String platformCode, String accountName, String accountHandle) {}
}
