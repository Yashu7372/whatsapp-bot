-- ============================================================================
-- V48 - Fix the DEMO tenant's demo password hash
--
-- V42 seeds ~21 DEMO-tenant users (and V45 one more) sharing a single bcrypt
-- hash, documented in both migrations as "the existing demo password
-- 'admin123'". It is not: bcrypt.checkpw(b'admin123', <that hash>) is False.
-- Nobody can actually log into any DEMO-tenant demo account with the
-- documented credential -- confirmed independently with Python's bcrypt
-- library, not just via the app. This silently blocked exactly the kind of
-- role-by-role walkthrough (login as director@aurelia.demo,
-- design.manager@meridian.demo, etc., per docs/e2e/
-- ENTERPRISE_PROJECT_CONTROL_E2E.md) the demo data exists to support.
--
-- Replace the stored hash with one that actually verifies against
-- 'admin123', scoped to rows that still carry the original broken hash so
-- this is idempotent and never touches a password anyone has since changed.
-- ============================================================================

UPDATE public.tenant_users
SET password_hash = '$2a$12$V8jj9pR.RefT7s1yGZ7C/.9dxZHAcUnr1L5ooODBbWOhD8BvfDq6i'
WHERE password_hash = '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY.5AEfAxwd6O3.';
