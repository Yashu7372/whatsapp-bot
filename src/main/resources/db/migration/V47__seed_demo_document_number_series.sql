-- ============================================================================
-- V47 - Seed document numbering series for the DEMO project's document-control
-- workflows.
--
-- DocumentNumberService.nextReference() throws 400 "No numbering series
-- defined" when no document_number_series row exists for a
-- (project_id, doc_type) pair. V46's SHOP_DRAWING and MATERIAL_SUBMITTAL
-- workflows let a user submit a document of either type on the demo project,
-- but no migration ever defined a series for them, so every live submission
-- through the API (including EnterpriseProjectControlE2ETest) failed before
-- a reference number could be issued.
-- ============================================================================

DO $$
DECLARE
    t uuid;
BEGIN
    SELECT id INTO t FROM public.tenants WHERE tenant_code='DEMO' LIMIT 1;
    IF t IS NULL THEN RETURN; END IF;

    INSERT INTO public.document_number_series(id,tenant_id,project_id,doc_type,prefix,next_number,padding,response_days)
    VALUES
      (gen_random_uuid(),t,'20000000-0000-0000-0000-000000000001','SHOP_DRAWING','SD',1,4,7),
      (gen_random_uuid(),t,'20000000-0000-0000-0000-000000000001','MATERIAL_SUBMITTAL','MS',1,4,10)
    ON CONFLICT (project_id, doc_type) DO NOTHING;
END $$;
