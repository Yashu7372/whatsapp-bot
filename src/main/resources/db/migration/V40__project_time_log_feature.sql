-- Splits "log/view my own hours" out of PROJECT_RESOURCE_COST (MANAGER/ADMIN-only) into its own
-- feature so any project participant can log and see their own time, while the money-bearing
-- endpoints (rates, approve, actual-costs) stay behind PROJECT_RESOURCE_COST unchanged.
-- is_core=true: logging your own work should never depend on a tenant's plan entitlement.
INSERT INTO public.feature_catalog (feature_code, module, nav_section, nav_label, nav_icon, route, min_role, is_core, sort_order, created_at)
VALUES ('PROJECT_TIME_LOG', 'PROJECT_CONTROL', NULL, 'Time Log', 'Clock', NULL, 'VIEWER', true, 100, now());

INSERT INTO public.feature_api_path (feature_code, path_pattern)
VALUES ('PROJECT_TIME_LOG', '^/api/v1/projects/[^/]+/time-log');

INSERT INTO public.role_permissions (role, feature_code, action, allowed)
VALUES
    ('ADMIN',    'PROJECT_TIME_LOG', 'VIEW',   true),
    ('MANAGER',  'PROJECT_TIME_LOG', 'VIEW',   true),
    ('REVIEWER', 'PROJECT_TIME_LOG', 'VIEW',   true),
    ('VIEWER',   'PROJECT_TIME_LOG', 'VIEW',   true),
    ('ADMIN',    'PROJECT_TIME_LOG', 'MANAGE', true),
    ('MANAGER',  'PROJECT_TIME_LOG', 'MANAGE', true),
    ('REVIEWER', 'PROJECT_TIME_LOG', 'MANAGE', true),
    ('VIEWER',   'PROJECT_TIME_LOG', 'MANAGE', true);
