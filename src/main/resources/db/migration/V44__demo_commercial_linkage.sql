-- V43 - Complete the enterprise demo's cross-feature story.
-- This migration intentionally reuses existing Document Control, budget and IPC tables so the
-- same work/cost/evidence can be followed through every specialist screen.

DO $$
DECLARE
    t uuid;
BEGIN
    SELECT id INTO t FROM public.tenants WHERE tenant_code='DEMO' LIMIT 1;
    IF t IS NULL THEN RETURN; END IF;

    -- Link the seeded time entries to the controlled documents they were supporting. Work item is
    -- the primary activity relationship; document remains optional secondary evidence/context.
    UPDATE public.timesheets SET document_id='a0000000-0000-0000-0000-000000000003'
      WHERE id='91000000-0000-0000-0000-000000000001' AND tenant_id=t;
    UPDATE public.timesheets SET document_id='a0000000-0000-0000-0000-000000000004'
      WHERE id='91000000-0000-0000-0000-000000000002' AND tenant_id=t;
    UPDATE public.timesheets SET document_id='a0000000-0000-0000-0000-000000000006'
      WHERE id='91000000-0000-0000-0000-000000000003' AND tenant_id=t;
    UPDATE public.timesheets SET document_id='a0000000-0000-0000-0000-000000000007'
      WHERE id IN ('91000000-0000-0000-0000-000000000004','91000000-0000-0000-0000-000000000005') AND tenant_id=t;

    -- Existing Project Controls uses the approved budget snapshot's actual_cost column. Keep that
    -- snapshot synchronized with the source ledger for this seeded demonstration so every screen
    -- reports the same captured actuals.
    UPDATE public.budget_lines b
       SET actual_cost = COALESCE((SELECT SUM(a.amount)
                                     FROM public.actual_cost_entries a
                                    WHERE a.tenant_id=t
                                      AND a.project_id=b.project_id
                                      AND a.budget_line_id=b.id),0)
     WHERE b.tenant_id=t
       AND b.budget_version_id='80000000-0000-0000-0000-000000000001';

    -- A project-progress measurement document is approved evidence for previously executed
    -- structural work. It is deliberately attached to ST-301, not created as an isolated finance
    -- row, so the IPC can drill back to the activity that produced the value.
    INSERT INTO public.documents(
        id,tenant_id,title,doc_type,description,current_version,status,project_id,originator_org_id,
        document_code,due_at,review_outcome,approved_value,security_classification,discipline,
        package_code,location_code,issue_purpose,current_revision_code,intake_channel,work_item_id,created_by)
    VALUES(
        'a0000000-0000-0000-0000-00000000000a',t,
        'Monthly Progress Measurement - Tower A Structure','PROGRESS_MEASUREMENT',
        'Measured and consultant-verified structural progress supporting the July payment application.',
        2,'APPROVED','20000000-0000-0000-0000-000000000001','10000000-0000-0000-0000-000000000003',
        'GBC-CRK-QS-0071',NULL,'CODE_A',8420000,'PROJECT','Commercial','STRUCT','TOWER-A',
        'FOR_APPROVAL','02','PORTAL','70000000-0000-0000-0000-000000000009','40000000-0000-0000-0000-00000000000f')
    ON CONFLICT(id) DO NOTHING;

    -- Current-version rows make the evidence complete when users open revision history.
    INSERT INTO public.document_versions(document_id,tenant_id,version_num,revision_code,change_notes,created_by)
    SELECT d.id,t,d.current_version,d.current_revision_code,'Seeded current controlled revision',d.created_by
      FROM public.documents d
     WHERE d.tenant_id=t
       AND d.id IN (
        'a0000000-0000-0000-0000-000000000001','a0000000-0000-0000-0000-000000000002',
        'a0000000-0000-0000-0000-000000000003','a0000000-0000-0000-0000-000000000004',
        'a0000000-0000-0000-0000-000000000005','a0000000-0000-0000-0000-000000000006',
        'a0000000-0000-0000-0000-000000000007','a0000000-0000-0000-0000-000000000008',
        'a0000000-0000-0000-0000-000000000009','a0000000-0000-0000-0000-00000000000a')
       AND NOT EXISTS (SELECT 1 FROM public.document_versions v WHERE v.document_id=d.id AND v.version_num=d.current_version);

    -- Two evidence-backed applications demonstrate the real sequence without moving money:
    -- contractor claim -> consultant certification -> client records external settlement.
    INSERT INTO public.payment_applications(
        id,tenant_id,project_id,application_ref,claimed_by_org_id,period_start,period_end,
        gross_claimed,previously_certified,retention_percent,retention_amount,net_certified,currency,
        status,submitted_at,certified_by,certified_at,paid_by,paid_at,payment_reference,created_by)
    VALUES
      ('c0000000-0000-0000-0000-000000000001',t,'20000000-0000-0000-0000-000000000001','IPC-006',
       '10000000-0000-0000-0000-000000000003','2026-07-01','2026-07-31',8420000,0,10,842000,7578000,'AED',
       'PAID','2026-08-02 09:00','40000000-0000-0000-0000-000000000008','2026-08-05 15:30',
       '40000000-0000-0000-0000-000000000003','2026-08-10 11:00','AUR-PAY-2026-0088','40000000-0000-0000-0000-00000000000f'),
      ('c0000000-0000-0000-0000-000000000002',t,'20000000-0000-0000-0000-000000000001','IPC-007',
       '10000000-0000-0000-0000-000000000003','2026-08-01','2026-08-15',1450000,7578000,10,145000,1305000,'AED',
       'CERTIFIED','2026-08-15 09:00','40000000-0000-0000-0000-000000000008','2026-08-16 10:30',
       NULL,NULL,NULL,'40000000-0000-0000-0000-00000000000f')
    ON CONFLICT(id) DO NOTHING;

    INSERT INTO public.payment_application_items(id,tenant_id,payment_application_id,document_id,description,amount)
    VALUES
      ('c1000000-0000-0000-0000-000000000001',t,'c0000000-0000-0000-0000-000000000001','a0000000-0000-0000-0000-00000000000a','Consultant-verified July structural progress',8420000),
      ('c1000000-0000-0000-0000-000000000002',t,'c0000000-0000-0000-0000-000000000002','a0000000-0000-0000-0000-000000000006','Approved Level 08 concrete pour progress',1450000)
    ON CONFLICT(id) DO NOTHING;
END $$;
