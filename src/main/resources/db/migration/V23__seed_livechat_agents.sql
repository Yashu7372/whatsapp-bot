-- =========================================================
-- V23: Seed default live-chat agents for all active tenants
--
-- The live-chat assignment/handover APIs (LiveChatService,
-- /api/v1/crm/conversations/{id}/assign) require at least one row in
-- tenant_agents, but no prior migration seeded any — so agent assignment
-- could never be exercised on a fresh database. Mirrors the V21 pattern
-- (per-tenant seed derived from tenant_code) so local dev and the
-- speedwheels end-to-end flow work out of the box.
-- =========================================================

INSERT INTO tenant_agents (tenant_id, name, email, role, active)
SELECT id,
       business_name || ' Support Agent',
       'agent@' || tenant_code || '.com',
       'AGENT',
       true
FROM tenants
WHERE active = true
ON CONFLICT ON CONSTRAINT uk_tenant_agents_email DO NOTHING;

INSERT INTO tenant_agents (tenant_id, name, email, role, active)
SELECT id,
       business_name || ' Manager',
       'manager@' || tenant_code || '.com',
       'MANAGER',
       true
FROM tenants
WHERE active = true
ON CONFLICT ON CONSTRAINT uk_tenant_agents_email DO NOTHING;
