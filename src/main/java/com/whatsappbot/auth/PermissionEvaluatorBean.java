package com.whatsappbot.auth;

import io.jsonwebtoken.Claims;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * SpEL root for declarative, per-endpoint checks: {@code @PreAuthorize("@perm.manage('BILLING')")}.
 * Delegates to the same {@link PermissionService} that {@link FeatureAuthorizationInterceptor} uses,
 * so this is an additional, fail-closed layer for specific high-value endpoints — not a replacement
 * for the interceptor's repo-wide coverage.
 */
@Component("perm")
@RequiredArgsConstructor
public class PermissionEvaluatorBean {

    private final PermissionService permissionService;

    public boolean view(String featureCode) {
        Claims claims = currentClaims();
        return claims != null && permissionService.canView(claims, featureCode);
    }

    public boolean manage(String featureCode) {
        Claims claims = currentClaims();
        return claims != null && permissionService.canManage(claims, featureCode);
    }

    public boolean isPlatformAdmin() {
        Claims claims = currentClaims();
        return claims != null && PermissionService.PLATFORM_SCOPE.equals(claims.get("scope"));
    }

    private Claims currentClaims() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof Claims claims
                ? claims
                : null;
    }
}
