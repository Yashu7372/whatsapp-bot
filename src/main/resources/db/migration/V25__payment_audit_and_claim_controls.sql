-- =====================================================================
-- V25 - Payment audit trail and claim controls
--
-- Document approval was tamper-evident but the money movement it authorises
-- was not recorded at all. A certified figure is only defensible if the steps
-- that produced it — who claimed, who certified, who released payment — carry
-- the same evidence weight as the approval underneath them.
--
-- Also closes two ways the same work could be paid for more than once.
-- =====================================================================

-- ── Hash-chained audit for the payment lifecycle ─────────────────────
-- Mirrors document_audit_events: each row commits to the one before it, so an
-- edit to a historic entry breaks the chain from that point on.
CREATE TABLE payment_audit_events (
    id                     UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id              UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    payment_application_id UUID         NOT NULL REFERENCES payment_applications(id) ON DELETE CASCADE,
    actor_user_id          UUID         REFERENCES tenant_users(id) ON DELETE SET NULL,
    event_type             VARCHAR(100) NOT NULL,
    event_payload          JSONB,
    event_hash             VARCHAR(128),
    previous_event_hash    VARCHAR(128),
    created_at             TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_payment_audit_events_app
    ON payment_audit_events(payment_application_id, created_at DESC);

-- ── Stop the same evidence being claimed twice ───────────────────────
-- The existing unique(payment_application_id, document_id) only prevented a
-- document appearing twice on one claim. Nothing stopped the same approved
-- document being claimed in full on three separate applications.
--
-- The rule that is actually needed — "not already claimed on an application that
-- has not been rejected" — spans two tables, so it cannot be a unique index.
-- PaymentApplicationService enforces it; this index makes that check cheap.
CREATE INDEX idx_payment_items_document_lookup
    ON payment_application_items(document_id)
    WHERE document_id IS NOT NULL;

-- ── Cap what can be claimed against a document ───────────────────────
-- Without a ceiling the only validation on an amount was that it was not
-- negative, so an approved document could carry a claim of any size.
ALTER TABLE documents ADD COLUMN approved_value NUMERIC(18, 2);
COMMENT ON COLUMN documents.approved_value IS
    'Value of work this document evidences. When set, a claim against it cannot exceed this.';

-- ── Record who released payment ──────────────────────────────────────
ALTER TABLE payment_applications ADD COLUMN paid_by UUID REFERENCES tenant_users(id) ON DELETE SET NULL;
ALTER TABLE payment_applications ADD COLUMN paid_at TIMESTAMP;
ALTER TABLE payment_applications ADD COLUMN payment_reference VARCHAR(120);
