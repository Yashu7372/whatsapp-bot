package com.whatsappbot.features;

import com.whatsappbot.auth.PermissionService;
import com.whatsappbot.subscription.TenantSubscriptionEntity;
import com.whatsappbot.subscription.TenantSubscriptionRepository;
import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
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
    private final FeatureCatalogRepository featureCatalogRepository;
    private final PermissionService permissionService;

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

    @GetMapping("/nav")
    public ResponseEntity<List<NavItem>> getMyNav(@AuthenticationPrincipal Claims claims) {
        Object tenantIdClaim = claims.get("tenantId");
        if (tenantIdClaim == null) {
            // Platform-scoped admins aren't tenant-scoped; the platform admin
            // console is a separate follow-up, not part of this nav.
            return ResponseEntity.ok(List.of());
        }
        UUID tenantId = UUID.fromString((String) tenantIdClaim);

        Set<String> enabledFeatureCodes = new HashSet<>(tenantFeatureRepository.findEnabledFeatureCodes(tenantId));

        List<NavItem> items = new ArrayList<>();
        for (FeatureCatalogEntity fc : featureCatalogRepository.findAllByOrderByModuleAscSortOrderAsc()) {
            boolean entitled = fc.isCore() || enabledFeatureCodes.contains(fc.getFeatureCode());
            if (!entitled || !permissionService.canView(claims, fc.getFeatureCode())) {
                continue;
            }
            items.add(new NavItem(fc.getFeatureCode(), fc.getModule(), fc.getNavSection(),
                    fc.getNavLabel(), fc.getNavIcon(), fc.getRoute(),
                    permissionService.canManage(claims, fc.getFeatureCode())));
        }

        return ResponseEntity.ok(items);
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

    public record NavItem(
            String featureCode,
            String module,
            String navSection,
            String navLabel,
            String navIcon,
            String route,
            boolean canManage) {}

    public record FeaturesResponse(
            String tenantId,
            String plan,
            String subscriptionStatus,
            Map<String, Boolean> features) {}

    public record ToggleRequest(boolean enabled) {}
}
