-- ============================================================================
-- V45 - Enterprise document-control E2E demonstration data
--
-- V42 already provides the multi-project client, consultant, contractor and
-- subcontractor hierarchy. This migration adds the missing operational fixture
-- needed to exercise a realistic document-control journey end-to-end:
-- contractor document controller -> consultant technical review -> client approval.
--
-- Demo phone numbers are deliberately non-routable placeholders and WhatsApp is
-- disabled in persisted demo preferences. Automated tests enable a synthetic E.164
-- destination only while WhatsAppGraphClient is mocked, so migrations can never
-- cause an external message to be sent accidentally.
-- ============================================================================

DO $$
DECLARE
    t uuid;
BEGIN
    SELECT id INTO t FROM public.tenants WHERE tenant_code='DEMO' LIMIT 1;
    IF t IS NULL THEN RETURN; END IF;

    -- Dedicated contractor document controller for the operational review stage.
    INSERT INTO public.tenant_users(
        id,tenant_id,email,password_hash,full_name,role,organization_id,
        job_title,department,active,email_notifications_enabled,
        whatsapp_notifications_enabled,notification_phone)
    VALUES(
        '40000000-0000-0000-0000-000000000016',t,
        'document.controller@gulfbuild.demo',
        '$2a$12$LQv3c1yqBWVHxkd0LHAkCOYz6TtxMQJqhN8/LewY.5AEfAxwd6O3.',
        'Noor Siddiqui','REVIEWER','10000000-0000-0000-0000-000000000003',
        'Document Controller','Project Controls',true,false,false,NULL)
    ON CONFLICT(email) DO UPDATE SET
        organization_id=excluded.organization_id,
        job_title=excluded.job_title,
        department=excluded.department,
        active=true,
        whatsapp_notifications_enabled=false,
        notification_phone=NULL;

    -- Shop drawings: contractor document-control gate, consultant technical review,
    -- then formal client approval. Assignment semantics are data, not Java logic.
    INSERT INTO public.document_control_workflows(
        id,tenant_id,name,doc_type,steps,active,created_at,updated_at)
    VALUES(
        'b0000000-0000-0000-0000-000000000001',t,
        'Construction Shop Drawing - Three Party Review','SHOP_DRAWING',
        '[
          {"name":"Contractor Document Control Check","authority":"INTERNAL_REVIEW","assignmentType":"USER","reviewerEmail":"document.controller@gulfbuild.demo","slaHours":8,"required":true},
          {"name":"Consultant Technical Review","authority":"TECHNICAL_REVIEW","assignmentType":"PARTY_ROLE","partyRole":"CONSULTANT","slaHours":48,"required":true},
          {"name":"Client Approval","authority":"CLIENT_APPROVAL","assignmentType":"PARTY_ROLE","partyRole":"CLIENT","slaHours":48,"required":true}
        ]'::jsonb,true,now(),now())
    ON CONFLICT(id) DO UPDATE SET
        name=excluded.name,steps=excluded.steps,active=true,updated_at=now();

    -- Material submittals follow the same company chain but retain their own template,
    -- allowing material-specific SLAs/rules to evolve independently later.
    INSERT INTO public.document_control_workflows(
        id,tenant_id,name,doc_type,steps,active,created_at,updated_at)
    VALUES(
        'b0000000-0000-0000-0000-000000000002',t,
        'Material Submittal - Three Party Review','MATERIAL_SUBMITTAL',
        '[
          {"name":"Contractor Document Control Check","authority":"INTERNAL_REVIEW","assignmentType":"USER","reviewerEmail":"document.controller@gulfbuild.demo","slaHours":8,"required":true},
          {"name":"Consultant Material Review","authority":"TECHNICAL_REVIEW","assignmentType":"PARTY_ROLE","partyRole":"CONSULTANT","slaHours":72,"required":true},
          {"name":"Client Approval","authority":"CLIENT_APPROVAL","assignmentType":"PARTY_ROLE","partyRole":"CLIENT","slaHours":48,"required":true}
        ]'::jsonb,true,now(),now())
    ON CONFLICT(id) DO UPDATE SET
        name=excluded.name,steps=excluded.steps,active=true,updated_at=now();
END $$;
