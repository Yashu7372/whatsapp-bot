-- =========================================================
-- V8 - Lead Intelligence
-- =========================================================

CREATE TABLE lead_signals (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID          NOT NULL REFERENCES tenants(id),
    contact_id      UUID          REFERENCES contacts(id),
    conversation_id UUID          REFERENCES conversations(id),
    signal_type     VARCHAR(50)   NOT NULL,
    intent_category VARCHAR(50),
    message_text    TEXT,
    score           DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    platform_code   VARCHAR(50)   NOT NULL DEFAULT 'WHATSAPP',
    metadata        JSONB         NOT NULL DEFAULT '{}',
    captured_at     TIMESTAMP     NOT NULL DEFAULT NOW(),
    created_at      TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_lead_signals_tenant_score    ON lead_signals(tenant_id, score DESC);
CREATE INDEX idx_lead_signals_contact         ON lead_signals(contact_id);
CREATE INDEX idx_lead_signals_tenant_captured ON lead_signals(tenant_id, captured_at DESC);
