package com.whatsappbot.features;

import com.whatsappbot.subscription.TenantSubscriptionEntity;
import com.whatsappbot.subscription.TenantSubscriptionRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class FeatureController {

    private final TenantFeatureRepository tenantFeatureRepository;
    private final TenantSubscriptionRepository tenantSubscriptionRepository;

    @GetMapping("/features")
    public ResponseEntity<FeaturesResponse> getMyFeatures(
            @AuthenticationPrincipal Claims claims) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));

        List<TenantFeatureEntity> featureEntities = tenantFeatureRepository.findAllByTenantId(tenantId);

        Map<String, Boolean> features = new LinkedHashMap<>();
        for (TenantFeatureEntity f : featureEntities) {
            features.put(f.getFeatureCode(), f.isEnabled());
        }

        TenantSubscriptionEntity sub = tenantSubscriptionRepository
                .findTopByTenantIdOrderByStartedAtDesc(tenantId)
                .orElse(null);

        String plan   = sub != null ? sub.getPlanCode() : "STARTER";
        String status = sub != null ? sub.getStatus()   : "TRIAL";

        return ResponseEntity.ok(new FeaturesResponse(tenantId.toString(), plan, status, features));
    }

    @PatchMapping("/features/{featureCode}")
    public ResponseEntity<Void> toggleFeature(
            @AuthenticationPrincipal Claims claims,
            @PathVariable String featureCode,
            @RequestBody ToggleRequest body) {

        UUID tenantId = UUID.fromString((String) claims.get("tenantId"));

        tenantFeatureRepository.findAllByTenantId(tenantId).stream()
                .filter(f -> f.getFeatureCode().equals(featureCode))
                .findFirst()
                .ifPresent(f -> {
                    f.setEnabled(body.enabled());
                    tenantFeatureRepository.save(f);
                });

        return ResponseEntity.noContent().build();
    }

    // ── Records ──────────────────────────────────────────────────────────

    public record FeaturesResponse(
            String tenantId,
            String plan,
            String subscriptionStatus,
            Map<String, Boolean> features) {}

    public record ToggleRequest(boolean enabled) {}
}
