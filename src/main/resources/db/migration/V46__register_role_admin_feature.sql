-- ============================================================================
-- V46 - Register the global Roles & Permissions admin page as a feature
--
-- RolesPermissions.tsx (/control/roles) and its API
-- (RolePermissionAdminController, /api/v1/admin/role-permissions) were never
-- given a feature_catalog / feature_api_path entry. That did not leave the
-- endpoint open: it is guarded by @PreAuthorize("@perm.isPlatformAdmin()"),
-- and scope=PLATFORM callers already bypass FeatureAuthorizationInterceptor
-- and PermissionService entirely (see PermissionService.can /
-- FeatureAuthorizationInterceptor.isEntitled). But it meant the interceptor
-- never matched the path at all -- a request from any tenant-scoped role
-- fell through the "not catalogued yet" fail-open branch and was only ever
-- stopped by the controller's own guard, with no explicit deny recorded in
-- role_permissions like every other admin surface.
--
-- This migration registers the path so the interceptor matches it
-- explicitly. Deliberately no role_permissions rows are seeded for
-- ADMIN/MANAGER/REVIEWER/VIEWER: PermissionService.can() defaults to false
-- when no row exists, so every tenant role is now denied by the interceptor
-- itself (defense in depth) in addition to the controller's platform-admin
-- check, which remains the authoritative gate. Nothing changes for
-- scope=PLATFORM callers.
-- ============================================================================

INSERT INTO public.feature_catalog(feature_code,module,nav_section,nav_label,nav_icon,route,min_role,is_core,sort_order,created_at)
VALUES('PLATFORM_ROLE_ADMIN','PLATFORM','Platform','Roles & Permissions','Settings2','/control/roles','ADMIN',false,10,now())
ON CONFLICT(feature_code) DO UPDATE SET nav_label=excluded.nav_label,nav_icon=excluded.nav_icon,route=excluded.route,sort_order=excluded.sort_order;

INSERT INTO public.feature_api_path(feature_code,path_pattern)
VALUES('PLATFORM_ROLE_ADMIN','^/api/v1/admin/role-permissions')
ON CONFLICT(path_pattern) DO UPDATE SET feature_code=excluded.feature_code;
