-- V37: External document intake channels (shareable upload links, WhatsApp) and malware-scan tracking.
--
-- Every intake channel funnels into the same documents/document_versions/media_assets tables the
-- authenticated upload path already uses, so the register, workflow and approval machinery need
-- no changes to recognise these documents. A file is never attached to a document until it has
-- been scanned; scan_status gates whether a version is ever handed back on a read path.

CREATE TABLE document_upload_links (
    id               UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID          NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    project_id       UUID          REFERENCES projects(id) ON DELETE CASCADE,
    doc_type         VARCHAR(100)  NOT NULL,
    label            VARCHAR(200)  NOT NULL,
    token            VARCHAR(64)   NOT NULL UNIQUE,
    password_hash    VARCHAR(100),
    max_uploads      INT,
    upload_count     INT           NOT NULL DEFAULT 0,
    expires_at       TIMESTAMP     NOT NULL,
    revoked_at       TIMESTAMP,
    created_by       UUID          REFERENCES tenant_users(id) ON DELETE SET NULL,
    created_at       TIMESTAMP     NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_upload_links_tenant ON document_upload_links(tenant_id);
CREATE INDEX idx_upload_links_token  ON document_upload_links(token);

-- Every attempt against a link: viewed, password failure, successful upload, rejected-expired.
-- This is what powers password-attempt rate limiting and gives an audit trail for a surface
-- that, by definition, is reachable without an authenticated user.
CREATE TABLE document_upload_link_events (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    link_id        UUID         NOT NULL REFERENCES document_upload_links(id) ON DELETE CASCADE,
    tenant_id      UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    event_type     VARCHAR(30)  NOT NULL,
    document_id    UUID         REFERENCES documents(id) ON DELETE SET NULL,
    uploader_name  VARCHAR(255),
    uploader_email VARCHAR(320),
    ip_address     VARCHAR(64),
    detail         VARCHAR(500),
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_upload_link_events_link ON document_upload_link_events(link_id, created_at DESC);

-- Issued after a successful password check (or immediately, for a password-less link), so the
-- upload call itself never has to re-transmit the password.
CREATE TABLE document_upload_link_sessions (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    link_id     UUID         NOT NULL REFERENCES document_upload_links(id) ON DELETE CASCADE,
    token       VARCHAR(64)  NOT NULL UNIQUE,
    expires_at  TIMESTAMP    NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_upload_link_sessions_token ON document_upload_link_sessions(token);

-- PORTAL = today's authenticated create; LINK / WHATSAPP / EMAIL are the new external channels.
ALTER TABLE documents
    ADD COLUMN upload_link_id  UUID REFERENCES document_upload_links(id) ON DELETE SET NULL,
    ADD COLUMN uploader_name   VARCHAR(255),
    ADD COLUMN uploader_email  VARCHAR(320),
    ADD COLUMN intake_channel  VARCHAR(30) NOT NULL DEFAULT 'PORTAL';

-- Existing rows predate scanning and were uploaded through the authenticated, already-trusted
-- path, so they default to CLEAN. Every asset written by the new intake pipeline sets this
-- explicitly (PENDING while scanning, then CLEAN or INFECTED) before it is ever attached to a
-- document version a user can see.
ALTER TABLE media_assets
    ADD COLUMN scan_status VARCHAR(30) NOT NULL DEFAULT 'CLEAN',
    ADD COLUMN scanned_at  TIMESTAMP;
