-- =====================================================================
-- V39 - Fix a regression introduced by V38's seed data
--
-- ProjectAccessService.isTenantAdministrator() treats ANY user with a
-- non-null organization_id as organization-scoped, regardless of their
-- tenant_users.role. V38 attached admin@speedwheels.com (the platform
-- tenant admin documented in LOCAL_E2E_CHECKLIST.md) to the demo CLIENT
-- organization "for convenience", which silently demoted it: it lost the
-- ability to configure workflow templates, edit the project capability
-- matrix, and everything else gated by requireProjectAdministrator().
--
-- admin@speedwheels.com must stay organization_id = NULL so at least one
-- real tenant administrator account exists locally. Use the seeded
-- client.admin@speedwheels-demo.local (from V38) to test CLIENT-org-scoped
-- behavior instead.
-- =====================================================================

UPDATE tenant_users
SET organization_id = NULL
WHERE email = 'admin@speedwheels.com';
