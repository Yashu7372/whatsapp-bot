-- ============================================================================
-- V49 - Give the DEMO tenant an active subscription
--
-- Discovered by actually walking the document create/upload flow end-to-end
-- (create an upload link, submit a document through it): every document
-- endpoint routes through FeatureAccessService.assertAccess(), which checks
-- assertSubscriptionActive() BEFORE assertFeatureEnabled() -- an
-- ResponseStatusException(402 PAYMENT_REQUIRED) if the tenant has no row in
-- tenant_subscriptions with status TRIAL/ACTIVE and a non-expired
-- (or null) expires_at. speedwheels and tastybites both got a BUSINESS/TRIAL
-- row; the DEMO tenant (added later, in V41.1) never did -- same gap pattern
-- as V47's missing tenant_features rows, just one layer up the stack. Every
-- document-control endpoint (create, upload links, delivery, intelligence,
-- security) was 402ing for every DEMO-tenant user regardless of role or the
-- V47 fix, independent of role_permissions/tenant_features entirely.
--
-- Mirror speedwheels' grant (BUSINESS plan, TRIAL status, no expiry) onto
-- DEMO. Guarded by NOT EXISTS so it's idempotent and never duplicates a
-- subscription a real billing flow has since created for this tenant.
-- ============================================================================

INSERT INTO public.tenant_subscriptions(tenant_id, plan_code, status, started_at)
SELECT t.id, 'BUSINESS', 'TRIAL', now()
FROM public.tenants t
WHERE t.tenant_code = 'DEMO'
  AND NOT EXISTS (SELECT 1 FROM public.tenant_subscriptions s WHERE s.tenant_id = t.id);
