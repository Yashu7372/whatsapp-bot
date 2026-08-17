package com.whatsappbot.features;

import com.whatsappbot.auth.PermissionService;
import com.whatsappbot.auth.RolePermissionEntity;
import com.whatsappbot.auth.RolePermissionRepository;
import com.whatsappbot.auth.UserRole;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;

/**
 * Global role x feature x action matrix admin. This table is platform-wide (not tenant-scoped),
 * so only a platform admin (scope=PLATFORM) may read or change it — a tenant ADMIN manages their
 * own team's role assignments (SETTINGS_TEAM) but never what each role is allowed to do.
 */
@RestController
@RequestMapping("/api/v1/admin/role-permissions")
@RequiredArgsConstructor
public class RolePermissionAdminController {

    private final FeatureCatalogRepository featureCatalogRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final PermissionService permissionService;

    @GetMapping
    @PreAuthorize("@perm.isPlatformAdmin()")
    public ResponseEntity<RolePermissionMatrixResponse> getMatrix() {

        List<FeatureCatalogSummary> features = featureCatalogRepository.findAllByOrderByModuleAscSortOrderAsc()
                .stream()
                .map(f -> new FeatureCatalogSummary(f.getFeatureCode(), f.getModule(), f.getNavLabel()))
                .toList();

        List<MatrixCell> cells = rolePermissionRepository.findAll().stream()
                .map(e -> new MatrixCell(e.getRole(), e.getFeatureCode(), e.getAction(), e.isAllowed()))
                .toList();

        List<String> roles = Arrays.stream(UserRole.values()).map(Enum::name).toList();
        List<String> actions = List.of(PermissionService.ACTION_VIEW, PermissionService.ACTION_MANAGE);

        return ResponseEntity.ok(new RolePermissionMatrixResponse(roles, actions, features, cells));
    }

    @PutMapping
    @PreAuthorize("@perm.isPlatformAdmin()")
    @Transactional
    public ResponseEntity<Void> updateMatrix(@RequestBody UpdateRolePermissionsRequest request) {

        for (MatrixCell cell : request.cells()) {
            RolePermissionEntity entity = rolePermissionRepository
                    .findByRoleAndFeatureCodeAndAction(cell.role(), cell.featureCode(), cell.action())
                    .orElseGet(RolePermissionEntity::new);
            entity.setRole(cell.role());
            entity.setFeatureCode(cell.featureCode());
            entity.setAction(cell.action());
            entity.setAllowed(cell.allowed());
            rolePermissionRepository.save(entity);
        }

        // The whole cache is invalidated rather than evicting individual keys — this endpoint is
        // low-traffic (admin-only) and role_permissions is small, so a full rebuild on next read is
        // cheap and simpler than tracking exactly which keys changed.
        permissionService.invalidateCache();

        return ResponseEntity.noContent().build();
    }

    public record RolePermissionMatrixResponse(
            List<String> roles,
            List<String> actions,
            List<FeatureCatalogSummary> features,
            List<MatrixCell> cells) {}

    public record FeatureCatalogSummary(String featureCode, String module, String navLabel) {}

    public record MatrixCell(String role, String featureCode, String action, boolean allowed) {}

    public record UpdateRolePermissionsRequest(List<MatrixCell> cells) {}
}
