-- =====================================================================
-- V35 - Notification dispatch hardening and document security authority
-- =====================================================================

-- The dispatcher previously moved an outbox row straight to DELIVERED at claim time, before the
-- in-app rows and channel deliveries derived from it existed. A failure between those two points
-- lost the notification permanently with no way to detect it. Claiming now parks the row in
-- PROCESSING and only a successful fan-out marks it DELIVERED, so a crashed pod leaves evidence
-- that the stale-claim sweeper can return to PENDING.
ALTER TABLE workflow_notification_outbox DROP CONSTRAINT ck_workflow_notification_status;
ALTER TABLE workflow_notification_outbox ADD CONSTRAINT ck_workflow_notification_status
    CHECK (status IN ('PENDING','PROCESSING','DELIVERED','FAILED'));
ALTER TABLE workflow_notification_outbox ADD COLUMN claimed_at TIMESTAMP;

-- Serves the stale-claim sweeper without scanning the whole outbox.
CREATE INDEX idx_workflow_notification_claimed
    ON workflow_notification_outbox(status, claimed_at);

-- The tenant delivery audit reads "newest first for this tenant". The existing pending index is
-- keyed on (status,next_attempt_at,created_at) and cannot serve it, so the audit degraded into a
-- sequential scan as the table grew.
CREATE INDEX idx_notification_delivery_tenant
    ON workflow_notification_deliveries(tenant_id, created_at DESC);

-- A delivery parked as SKIPPED because its channel was switched off must become eligible again
-- once the channel is switched back on, so the claim query also considers SKIPPED rows whose
-- next_attempt_at has matured.
CREATE INDEX idx_notification_delivery_retry
    ON workflow_notification_deliveries(status, next_attempt_at)
    WHERE status IN ('PENDING','FAILED','SKIPPED');

-- Managing a document's security classification and its access grants is a stronger authority than
-- editing its content: an EDIT holder could previously declassify a RESTRICTED document or grant
-- itself ISSUE. MANAGE is a distinct permission code that implies VIEW/EDIT/ISSUE in code.
-- NOT VALID keeps the migration safe against legacy rows: it constrains every new grant without
-- forcing a validating scan of data written before the codes were fixed.
ALTER TABLE document_access_grants ADD CONSTRAINT ck_document_grant_permission
    CHECK (permission_code IN ('VIEW','EDIT','ISSUE','MANAGE')) NOT VALID;

-- Supports the paginated, authorization-aware document register query that replaces the previous
-- load-every-row-then-filter-per-row listing.
CREATE INDEX idx_documents_tenant_updated ON documents(tenant_id, updated_at DESC);
CREATE INDEX idx_documents_tenant_type_updated ON documents(tenant_id, doc_type, updated_at DESC);

-- The register query and the grant check both probe grants by document + principal + permission.
CREATE INDEX idx_document_grants_lookup
    ON document_access_grants(document_id, permission_code, user_id, organization_id, role_code);
