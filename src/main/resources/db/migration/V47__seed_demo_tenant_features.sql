-- ============================================================================
-- V47 - Grant the DEMO tenant the same feature entitlements as every other
-- tenant
--
-- tenant_features gates every non-core feature_catalog row (is_core=false):
-- FeatureAuthorizationInterceptor.isEntitled() denies a request for a
-- non-core feature unless the caller's tenant has an enabled row here, and
-- FeatureController.getMyNav() applies the same check before a nav item is
-- even offered. speedwheels and tastybites (seeded in V1) both received a
-- full tenant_features grant; the DEMO tenant (added later, in V41.1) never
-- did. Since almost every Enterprise Project Control feature -- Documents,
-- Workflows, Transmittals, Approvals, Security & Access, Budget & IPC,
-- Commercial facts, Forecast intelligence, Resource & cost, Audit,
-- Notifications, Upload links -- is is_core=false, DEMO tenant users of
-- every role (including ADMIN) were silently blocked from all of them: the
-- nav never listed the pages and the APIs 403'd, even though role_permissions
-- grants ADMIN access to all of them. Confirmed via a live role-by-role E2E
-- run against this migration chain (V1-V46) before this fix, and again after.
--
-- Mirrors speedwheels' grant exactly, including the three add-ons left
-- disabled there (BYO_DOCUMENT_STORAGE, BYO_MEDIA_STORAGE, CUSTOMER_KMS --
-- paid storage add-ons, not part of the base plan for any demo tenant).
-- ============================================================================

DO $$
DECLARE
    t uuid;
BEGIN
    SELECT id INTO t FROM public.tenants WHERE tenant_code='DEMO' LIMIT 1;
    IF t IS NULL THEN RETURN; END IF;

    INSERT INTO public.tenant_features(tenant_id, feature_code, enabled, enabled_at)
    SELECT t, feature_code, enabled, CASE WHEN enabled THEN now() END
    FROM (VALUES
        ('AI_CONTENT_GENERATOR',   true),
        ('AI_TREND_PICKER',        true),
        ('BYO_DOCUMENT_STORAGE',   false),
        ('BYO_MEDIA_STORAGE',      false),
        ('CAMPAIGNS',              true),
        ('CONTENT_APPROVALS',      true),
        ('CRM_DASHBOARD',          true),
        ('CUSTOMER_KMS',           false),
        ('DOCUMENT_AI_ANALYZER',  true),
        ('DOCUMENT_CONTROL',       true),
        ('INSTAGRAM_PUBLISHING',   true),
        ('LEAD_INTELLIGENCE',      true),
        ('LEARNING_INSIGHTS',      true),
        ('MEDIA_LIBRARY',          true),
        ('PLATFORM_INTEGRATIONS',  true),
        ('PROJECT_AI_INSIGHTS',    true),
        ('PROJECT_APPROVALS',      true),
        ('PROJECT_AUDIT',          true),
        ('PROJECT_BUDGET_IPC',     true),
        ('PROJECT_COMMITMENTS',    true),
        ('PROJECT_CONTROLS_CORE',  true),
        ('PROJECT_CONTROL_SUITE',  true),
        ('PROJECT_DOCUMENTS',      true),
        ('PROJECT_FORECASTING',    true),
        ('PROJECT_NOTIFICATIONS',  true),
        ('PROJECT_OVERVIEW',       true),
        ('PROJECT_RESOURCE_COST',  true),
        ('PROJECT_SECURITY',       true),
        ('PROJECT_TRANSMITTALS',   true),
        ('PROJECT_UPLOAD_LINKS',   true),
        ('PROJECT_WORKFLOWS',      true),
        ('SCHEDULED_PUBLISHING',   true),
        ('SETTINGS_SOCIAL',        true),
        ('SETTINGS_STORAGE',       true),
        ('VIDEO_TEMPLATE_ENGINE',  true),
        ('WHATSAPP_BOT',           true),
        ('YOUTUBE_PUBLISHING',     true),
        ('ZERO_KNOWLEDGE_STORAGE', true)
    ) AS feature_grant(feature_code, enabled)
    ON CONFLICT (tenant_id, feature_code) DO UPDATE SET enabled=excluded.enabled;
END $$;
