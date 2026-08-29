-- ============================================================================
-- V50 - Seed a document number series per (project, doc type) for the
-- Aurelia demo portfolio
--
-- Found by walking the actual "create/upload a document" flow end-to-end:
-- DocumentNumberService.nextReference() (called from every document-creation
-- path -- upload links, the direct create API) requires a
-- document_number_series row for (tenant, project, docType) and throws
-- "No numbering series defined for '<type>' on this project" (400) when none
-- exists. V42/V45 insert ~20 documents directly via SQL, which bypasses
-- nextReference() entirely, so the gap was invisible until a real upload was
-- attempted. Zero rows existed in document_number_series for any project in
-- any tenant -- every document upload was broken for the whole Aurelia
-- portfolio, for every user, regardless of role/tenant_features/subscription.
--
-- There is also no frontend page for POST /api/v1/projects/{id}/number-series
-- (admin-only, project-config endpoint) in this branch's CRM, so a tenant
-- admin has no way to self-serve this today either -- seeding it is the only
-- way the documented upload flow works out of the box.
--
-- One row per doc type actually used across the seeded AUR-CRK/AUR-BDT/AUR-MAR
-- documents and V45's document-control workflow templates, mirroring
-- DocumentNumberService.defineSeries' own default (prefix = doc type,
-- padding = 4).
-- ============================================================================

INSERT INTO public.document_number_series(tenant_id, project_id, doc_type, prefix, padding)
SELECT p.tenant_id, p.id, dt.doc_type, dt.doc_type, 4
FROM public.projects p
CROSS JOIN (VALUES
    ('SHOP_DRAWING'), ('MATERIAL_SUBMITTAL'), ('CALCULATION'), ('CLIENT_REQUIREMENT'),
    ('DESIGN_REPORT'), ('INSPECTION_REQUEST'), ('PROGRESS_MEASUREMENT'), ('TEST_REPORT')
) AS dt(doc_type)
WHERE p.project_code IN ('AUR-CRK', 'AUR-BDT', 'AUR-MAR')
  AND NOT EXISTS (
      SELECT 1 FROM public.document_number_series s
      WHERE s.project_id = p.id AND s.doc_type = dt.doc_type
  );
