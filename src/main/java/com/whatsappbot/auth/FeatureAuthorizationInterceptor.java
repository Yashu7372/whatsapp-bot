package com.whatsappbot.auth;

import com.whatsappbot.features.FeatureApiPathEntity;
import com.whatsappbot.features.FeatureApiPathRepository;
import com.whatsappbot.features.FeatureCatalogEntity;
import com.whatsappbot.features.FeatureCatalogRepository;
import com.whatsappbot.features.TenantFeatureRepository;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Single choke point for API authorization, data-driven from
 * feature_catalog / feature_api_path / role_permissions instead of
 * @PreAuthorize scattered across ~40 controllers. A request whose path
 * matches no row in feature_api_path is NOT gated here (fails open —
 * see V42 migration comment for which endpoints are deliberately still
 * unmapped and why).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FeatureAuthorizationInterceptor implements HandlerInterceptor {

    private final FeatureApiPathRepository featureApiPathRepository;
    private final FeatureCatalogRepository featureCatalogRepository;
    private final TenantFeatureRepository tenantFeatureRepository;
    private final PermissionService permissionService;

    private List<Map.Entry<Pattern, String>> compiledPatterns;
    private Map<String, FeatureCatalogEntity> catalogByCode;

    @PostConstruct
    void init() {
        compiledPatterns = featureApiPathRepository.findAll().stream()
                .map(p -> Map.entry(Pattern.compile(p.getPathPattern()), p.getFeatureCode()))
                .collect(Collectors.toList());
        catalogByCode = featureCatalogRepository.findAll().stream()
                .collect(Collectors.toMap(FeatureCatalogEntity::getFeatureCode, c -> c));
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                              @NonNull HttpServletResponse response,
                              @NonNull Object handler) throws Exception {
        String path = request.getRequestURI();

        String featureCode = compiledPatterns.stream()
                .filter(e -> e.getKey().matcher(path).find())
                .map(Map.Entry::getValue)
                .findFirst()
                .orElse(null);
        if (featureCode == null) {
            return true; // not catalogued yet — preserve current behavior
        }

        Object principal = SecurityContextHolder.getContext().getAuthentication() != null
                ? SecurityContextHolder.getContext().getAuthentication().getPrincipal()
                : null;
        if (!(principal instanceof Claims claims)) {
            return true; // no JWT in context: either a permitAll path or already rejected upstream
        }

        String action = "GET".equalsIgnoreCase(request.getMethod()) || "HEAD".equalsIgnoreCase(request.getMethod())
                ? PermissionService.ACTION_VIEW
                : PermissionService.ACTION_MANAGE;

        if (!isEntitled(claims, featureCode) || !permissionService.can(claims, featureCode, action)) {
            log.debug("Blocked {} {} — featureCode={} action={} role={}",
                    request.getMethod(), path, featureCode, action, claims.get("role"));
            response.sendError(HttpServletResponse.SC_FORBIDDEN, "Not permitted for this feature");
            return false;
        }
        return true;
    }

    private boolean isEntitled(Claims claims, String featureCode) {
        if (PermissionService.PLATFORM_SCOPE.equals(claims.get("scope"))) {
            return true;
        }
        FeatureCatalogEntity catalogEntry = catalogByCode.get(featureCode);
        if (catalogEntry != null && catalogEntry.isCore()) {
            return true;
        }
        Object tenantIdClaim = claims.get("tenantId");
        if (tenantIdClaim == null) {
            return false;
        }
        UUID tenantId = UUID.fromString((String) tenantIdClaim);
        return tenantFeatureRepository.existsByTenantIdAndFeatureCodeAndEnabledTrue(tenantId, featureCode);
    }
}
