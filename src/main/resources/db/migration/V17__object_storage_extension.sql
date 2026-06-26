-- =========================================================
-- V17 - Object Storage Extension
-- Extends media_assets to support cloud object storage
-- alongside the existing local-file path for dev mode
-- =========================================================

ALTER TABLE media_assets
    ADD COLUMN IF NOT EXISTS storage_provider VARCHAR(100)  NOT NULL DEFAULT 'LOCAL',
    ADD COLUMN IF NOT EXISTS bucket_name      VARCHAR(255),
    ADD COLUMN IF NOT EXISTS object_key       TEXT,
    ADD COLUMN IF NOT EXISTS checksum_sha256  VARCHAR(128),
    ADD COLUMN IF NOT EXISTS visibility       VARCHAR(50)   NOT NULL DEFAULT 'PRIVATE',
    ADD COLUMN IF NOT EXISTS status           VARCHAR(50)   NOT NULL DEFAULT 'UPLOADED',
    ADD COLUMN IF NOT EXISTS created_by       UUID          REFERENCES tenant_users(id) ON DELETE SET NULL,
    ADD COLUMN IF NOT EXISTS updated_at       TIMESTAMP     NOT NULL DEFAULT NOW();

-- Backfill existing rows: treat stored_path as the object_key for LOCAL provider
UPDATE media_assets
SET object_key = stored_path
WHERE object_key IS NULL;

CREATE INDEX IF NOT EXISTS idx_media_assets_tenant_type     ON media_assets(tenant_id, asset_type);
CREATE INDEX IF NOT EXISTS idx_media_assets_tenant_status   ON media_assets(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_media_assets_object_key      ON media_assets(storage_provider, object_key);

-- Pending upload tokens for signed-URL flow (expires fast)
CREATE TABLE storage_upload_tokens (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    token          VARCHAR(200) NOT NULL UNIQUE,
    object_key     TEXT         NOT NULL,
    content_type   VARCHAR(255),
    size_bytes     BIGINT,
    expires_at     TIMESTAMP    NOT NULL,
    used           BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_upload_tokens_token      ON storage_upload_tokens(token, used, expires_at);
CREATE INDEX idx_upload_tokens_tenant     ON storage_upload_tokens(tenant_id);
