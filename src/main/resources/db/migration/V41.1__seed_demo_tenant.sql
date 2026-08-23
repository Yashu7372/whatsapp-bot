-- ============================================================================
-- V41.1 - Seed the DEMO tenant required by the enterprise project-control fixtures
--
-- V42, V43 and V45 all seed the enterprise demo dataset (organizations, users,
-- projects, work items, budgets, document-control workflows) only when a tenant
-- with tenant_code='DEMO' already exists, and silently no-op otherwise
-- (`SELECT id INTO t ...; IF t IS NULL THEN RETURN; END IF;`). No migration ever
-- created that tenant, so the whole enterprise demo dataset was never seeded and
-- EnterpriseProjectControlE2ETest failed looking it up.
--
-- This tenant carries no WhatsApp messaging traffic, so phone_number_id is a
-- non-routable placeholder. business_type is GENERAL_SUPPORT because the
-- BusinessType enum has no construction/project-delivery specific value and
-- this project's rules forbid adding per-vertical branches for an enum label.
-- ============================================================================

INSERT INTO public.tenants (
    id, tenant_code, business_name, business_type, phone_number_id,
    system_prompt, active
) VALUES (
    'e0000000-0000-0000-0000-000000000001',
    'DEMO',
    'Aurelia Developments PJSC',
    'GENERAL_SUPPORT',
    'DEMO_TENANT_NO_WHATSAPP',
    'Enterprise project-control demo tenant. Not used for WhatsApp messaging; document-control and project-delivery fixtures are seeded against it.',
    true
)
ON CONFLICT (tenant_code) DO NOTHING;
