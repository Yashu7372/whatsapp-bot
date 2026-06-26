-- =========================================================
-- V18 - Document Zero-Knowledge Support
-- Adds encryption metadata, access grants, and audit trail
-- =========================================================

-- Per-asset encryption envelope (server never stores plaintext keys)
CREATE TABLE document_encryption_metadata (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    asset_id          UUID         NOT NULL REFERENCES media_assets(id) ON DELETE CASCADE,
    tenant_id         UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    encryption_alg    VARCHAR(100) NOT NULL DEFAULT 'AES-GCM-256',
    key_id            VARCHAR(200),
    encrypted_file_key TEXT,
    iv_base64         TEXT         NOT NULL,
    auth_tag_base64   TEXT,
    ciphertext_sha256 VARCHAR(128) NOT NULL,
    plaintext_sha256  VARCHAR(128),
    created_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_doc_enc_meta_asset    ON document_encryption_metadata(asset_id);
CREATE INDEX idx_doc_enc_meta_tenant   ON document_encryption_metadata(tenant_id);

-- Fine-grained permission grants per document
CREATE TABLE document_access_grants (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    document_id     UUID         NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    user_id         UUID         REFERENCES tenant_users(id) ON DELETE CASCADE,
    role_code       VARCHAR(100),
    permission_code VARCHAR(100) NOT NULL,
    granted_by      UUID         REFERENCES tenant_users(id) ON DELETE SET NULL,
    expires_at      TIMESTAMP,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_doc_access_grants_doc    ON document_access_grants(document_id, permission_code);
CREATE INDEX idx_doc_access_grants_user   ON document_access_grants(user_id, permission_code);
CREATE INDEX idx_doc_access_grants_tenant ON document_access_grants(tenant_id);

-- Append-only tamper-evident audit trail for documents
CREATE TABLE document_audit_events (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id           UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    document_id         UUID         NOT NULL REFERENCES documents(id) ON DELETE CASCADE,
    actor_user_id       UUID         REFERENCES tenant_users(id) ON DELETE SET NULL,
    event_type          VARCHAR(100) NOT NULL,
    event_payload       JSONB,
    event_hash          VARCHAR(128),
    previous_event_hash VARCHAR(128),
    created_at          TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_doc_audit_events_doc    ON document_audit_events(document_id, created_at DESC);
CREATE INDEX idx_doc_audit_events_tenant ON document_audit_events(tenant_id, created_at DESC);
