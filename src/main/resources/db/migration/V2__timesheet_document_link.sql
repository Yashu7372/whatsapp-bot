-- Links a logged time entry to the specific document it was worked on, mirroring the existing
-- material_receipts.document_id pattern (nullable — not every timesheet is document-scoped).
ALTER TABLE public.timesheets ADD COLUMN document_id uuid;

ALTER TABLE public.timesheets
    ADD CONSTRAINT timesheets_document_id_fkey FOREIGN KEY (document_id)
        REFERENCES public.documents(id) ON DELETE SET NULL;
